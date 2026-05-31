"""
项目脚手架模块 - 解耦模板引擎和文件系统

异步执行回调通知特性：
- 异步任务执行（不阻塞调用者）
- 进度回调
- 钩子系统：before/after_success/after_failure
- 任务ID + 状态追踪
"""

from __future__ import annotations

import asyncio
import json
import os
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from enum import Enum
from typing import Any, Callable, Dict, List, Optional
from uuid import uuid4

from src.domain.contracts.template import FileSystemProtocol, TemplateEngineProtocol
from src.domain.errors.template import ScaffoldError
from src.domain.models.common import ScaffoldConfig


@dataclass
class TemplateInfo:
    name: str
    description: str = ""
    project_type: str = ""
    language: str = ""
    parameters: List[Dict[str, Any]] = field(default_factory=list)
    path: str = ""


@dataclass
class ScaffoldResult:
    success: bool
    project_name: str
    created_files: List[str] = field(default_factory=list)
    errors: List[str] = field(default_factory=list)
    task_id: str = ""


class TaskStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"


@dataclass
class ScaffoldTask:
    task_id: str
    config: ScaffoldConfig
    status: TaskStatus = TaskStatus.PENDING
    result: Optional[ScaffoldResult] = None
    progress: float = 0.0
    progress_message: str = ""
    created_at: float = field(default_factory=lambda: __import__("time").time())
    completed_at: Optional[float] = None


class ScaffoldHook(ABC):
    """脚手架钩子 - 生命周期回调接口"""

    async def before_generate(self, config: ScaffoldConfig) -> None:
        """生成前回调"""
        pass

    async def on_progress(self, config: ScaffoldConfig, progress: float, message: str) -> None:
        """进度更新回调"""
        pass

    async def after_success(self, config: ScaffoldConfig, result: ScaffoldResult) -> None:
        """成功后回调"""
        pass

    async def after_failure(self, config: ScaffoldConfig, error: Exception) -> None:
        """失败后回调"""
        pass


class SimpleHook(ScaffoldHook):
    """简单回调钩子 - 包装函数为钩子
    自动检测并正确调用同步/异步函数
    """

    def __init__(
        self,
        before: Optional[Callable[[ScaffoldConfig], Any]] = None,
        progress: Optional[Callable[[ScaffoldConfig, float, str], Any]] = None,
        success: Optional[Callable[[ScaffoldConfig, ScaffoldResult], Any]] = None,
        failure: Optional[Callable[[ScaffoldConfig, Exception], Any]] = None,
    ) -> None:
        self._before = before
        self._progress = progress
        self._success = success
        self._failure = failure

    async def _call(self, func: Optional[Callable[..., Any]], *args: Any) -> None:
        """安全调用函数 - 处理同步/异步"""
        if func is None:
            return
        result = func(*args)
        if asyncio.iscoroutine(result):
            await result

    async def before_generate(self, config: ScaffoldConfig) -> None:
        await self._call(self._before, config)

    async def on_progress(self, config: ScaffoldConfig, progress: float, message: str) -> None:
        await self._call(self._progress, config, progress, message)

    async def after_success(self, config: ScaffoldConfig, result: ScaffoldResult) -> None:
        await self._call(self._success, config, result)

    async def after_failure(self, config: ScaffoldConfig, error: Exception) -> None:
        await self._call(self._failure, config, error)


class TemplateRegistry:
    def __init__(self, base_dir: str, file_system: FileSystemProtocol) -> None:
        self._base_dir = base_dir
        self._fs = file_system
        self._templates: Dict[str, TemplateInfo] = {}
        self._load_templates()

    def _load_templates(self) -> None:
        try:
            dirs = self._fs.list_dir(self._base_dir)
            for dir_name in dirs:
                dir_name = dir_name.rstrip("/")
                config_path = f"{self._base_dir}/{dir_name}/template.json"
                if self._fs.exists(config_path):
                    try:
                        content = self._fs.read_file(config_path)
                        config = json.loads(content)
                        info = TemplateInfo(
                            name=dir_name,
                            description=config.get("description", ""),
                            project_type=config.get("project_type", ""),
                            language=config.get("language", ""),
                            parameters=config.get("parameters", []),
                            path=f"{self._base_dir}/{dir_name}",
                        )
                        self._templates[dir_name] = info
                    except (json.JSONDecodeError, Exception):
                        continue
        except Exception:
            pass

    def list_templates(self) -> List[TemplateInfo]:
        return list(self._templates.values())

    def get_template(self, name: str) -> Optional[TemplateInfo]:
        return self._templates.get(name)

    def search_templates(self, **kwargs: Any) -> List[TemplateInfo]:
        results = []
        for tmpl in self._templates.values():
            match = True
            for key, value in kwargs.items():
                if hasattr(tmpl, key) and getattr(tmpl, key) != value:
                    match = False
                    break
            if match:
                results.append(tmpl)
        return results

    def count_files(self, template_path: str) -> int:
        """统计模板中的文件数量 - 用于进度计算"""
        count = 0
        try:
            entries = self._fs.list_dir(template_path)
            for entry in entries:
                if entry.endswith("/"):
                    count += self.count_files(f"{template_path}/{entry.rstrip('/')}")
                elif entry != "template.json":
                    count += 1
        except Exception:
            pass
        return count


class ProjectScaffold:
    """
    项目脚手架 - 异步执行 + 回调通知

    特性：
    - generate_async: 异步任务执行，返回任务ID
    - generate_sync: 同步执行
    - 钩子系统：before/progress/success/failure
    - 任务状态追踪
    """

    def __init__(
        self,
        template_engine: TemplateEngineProtocol,
        file_system: FileSystemProtocol,
        template_registry: TemplateRegistry,
    ) -> None:
        self._engine = template_engine
        self._fs = file_system
        self._registry = template_registry
        self._hooks: List[ScaffoldHook] = []
        self._tasks: Dict[str, ScaffoldTask] = {}

    def add_hook(self, hook: ScaffoldHook) -> None:
        """添加钩子"""
        self._hooks.append(hook)

    def remove_hook(self, hook: ScaffoldHook) -> None:
        """移除钩子"""
        if hook in self._hooks:
            self._hooks.remove(hook)

    def add_simple_hook(
        self,
        before: Optional[Callable[[ScaffoldConfig], None]] = None,
        progress: Optional[Callable[[ScaffoldConfig, float, str], None]] = None,
        success: Optional[Callable[[ScaffoldConfig, ScaffoldResult], None]] = None,
        failure: Optional[Callable[[ScaffoldConfig, Exception], None]] = None,
    ) -> SimpleHook:
        """添加简单函数钩子"""
        hook = SimpleHook(before=before, progress=progress, success=success, failure=failure)
        self.add_hook(hook)
        return hook

    def get_task(self, task_id: str) -> Optional[ScaffoldTask]:
        """获取任务状态"""
        return self._tasks.get(task_id)

    def list_tasks(self, status: Optional[TaskStatus] = None) -> List[ScaffoldTask]:
        """列出任务"""
        tasks = list(self._tasks.values())
        if status:
            tasks = [t for t in tasks if t.status == status]
        return tasks

    async def generate(self, config: ScaffoldConfig) -> ScaffoldResult:
        """同步执行 - 保留原有API兼容"""
        task_id = str(uuid4())
        return await self._do_generate(task_id, config)

    async def generate_async(self, config: ScaffoldConfig) -> str:
        """异步执行 - 立即返回任务ID，后台执行

        Returns:
            task_id: 任务ID，用于查询状态
        """
        task_id = str(uuid4())
        task = ScaffoldTask(task_id=task_id, config=config, status=TaskStatus.PENDING)
        self._tasks[task_id] = task

        asyncio.create_task(self._async_generate_wrapper(task_id, config))

        return task_id

    async def _async_generate_wrapper(self, task_id: str, config: ScaffoldConfig) -> None:
        """异步任务包装器"""
        task = self._tasks[task_id]
        task.status = TaskStatus.RUNNING

        try:
            result = await self._do_generate(task_id, config)
            task.result = result
            task.status = TaskStatus.COMPLETED
            task.completed_at = __import__("time").time()
        except Exception as e:
            task.status = TaskStatus.FAILED
            task.completed_at = __import__("time").time()
            await self._fire_failure_hooks(config, e)

    async def _fire_before_hooks(self, config: ScaffoldConfig) -> None:
        for hook in self._hooks:
            await hook.before_generate(config)

    async def _fire_progress_hooks(self, config: ScaffoldConfig, progress: float, message: str) -> None:
        for hook in self._hooks:
            await hook.on_progress(config, progress, message)

    async def _fire_success_hooks(self, config: ScaffoldConfig, result: ScaffoldResult) -> None:
        for hook in self._hooks:
            await hook.after_success(config, result)

    async def _fire_failure_hooks(self, config: ScaffoldConfig, error: Exception) -> None:
        for hook in self._hooks:
            await hook.after_failure(config, error)

    async def _do_generate(self, task_id: str, config: ScaffoldConfig) -> ScaffoldResult:
        """实际执行生成逻辑"""
        result = ScaffoldResult(success=False, project_name=config.project_name, task_id=task_id)

        # 执行before钩子
        await self._fire_before_hooks(config)

        template_info = self._registry.get_template(config.template)
        if not template_info:
            error_msg = f"Template not found: {config.template}"
            result.errors.append(error_msg)
            await self._fire_failure_hooks(config, Exception(error_msg))
            return result

        try:
            context = {
                "project_name": config.project_name,
                "project_type": config.project_type,
                "language": config.language,
                "author": config.author,
                **config.parameters,
            }

            total_files = self._registry.count_files(template_info.path)
            processed_files = 0

            # 更新任务状态
            if task_id in self._tasks:
                self._tasks[task_id].progress = 0.0
                self._tasks[task_id].progress_message = "Starting generation..."

            await self._fire_progress_hooks(config, 0.0, "Starting generation...")

            self._process_directory(
                template_info.path, config.output_dir, context, result,
                config, total_files, processed_files, task_id
            )

            result.success = len(result.errors) == 0

            # 完成进度
            if task_id in self._tasks:
                self._tasks[task_id].progress = 1.0
                self._tasks[task_id].progress_message = "Completed!"

            await self._fire_progress_hooks(config, 1.0, "Completed!")
            await self._fire_success_hooks(config, result)

        except Exception as e:
            result.errors.append(f"Generation failed: {e}")
            await self._fire_failure_hooks(config, e)

        return result

    def _process_directory(
        self,
        template_dir: str,
        output_dir: str,
        context: Dict[str, Any],
        result: ScaffoldResult,
        config: ScaffoldConfig,
        total_files: int,
        processed_files: int,
        task_id: str,
    ) -> int:
        try:
            entries = self._fs.list_dir(template_dir)
        except Exception:
            return processed_files

        for entry in entries:
            if entry.endswith("/"):
                sub_dir = entry.rstrip("/")
                processed_files = self._process_directory(
                    f"{template_dir}/{sub_dir}",
                    f"{output_dir}/{sub_dir}",
                    context,
                    result,
                    config,
                    total_files,
                    processed_files,
                    task_id,
                )
            else:
                if entry == "template.json":
                    continue

                rendered_name = self._engine.render_string(entry, context)
                src_path = f"{template_dir}/{entry}"
                dst_path = f"{output_dir}/{rendered_name}"

                try:
                    content = self._fs.read_file(src_path)
                    rendered = self._engine.render_string(content, context)
                    self._fs.write_file(dst_path, rendered)
                    result.created_files.append(dst_path)

                    processed_files += 1
                    progress = processed_files / total_files if total_files > 0 else 0.0

                    if task_id in self._tasks:
                        self._tasks[task_id].progress = progress
                        self._tasks[task_id].progress_message = f"Processed: {rendered_name}"

                    asyncio.create_task(
                        self._fire_progress_hooks(config, progress, f"Processed: {rendered_name}")
                    )

                except Exception as e:
                    result.errors.append(f"Failed to process {entry}: {e}")

        return processed_files
