"""
项目脚手架生成实现
关键设计：只依赖抽象协议，不依赖具体实现
- TemplateEngineProtocol: 模板渲染引擎抽象
- FileSystemProtocol: 文件系统抽象

这样设计的好处：
1. 单元测试时可以注入 InMemoryFileSystem 和 MockTemplateEngine
2. 不依赖真实文件系统和具体模板引擎
3. 可替换性强，未来可以轻松切换模板引擎
"""

from __future__ import annotations

import fnmatch
import os
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Protocol

from src.core import (
    FileSystemProtocol,
    ScaffoldConfig,
    ScaffoldError,
    TemplateEngineProtocol,
    TemplateError,
    LoggerProtocol,
)


@dataclass
class TemplateInfo:
    name: str
    description: str
    project_type: str
    language: str
    path: str
    parameters: List[Dict[str, Any]] = field(default_factory=list)


@dataclass
class ScaffoldResult:
    success: bool
    project_path: str
    created_files: List[str] = field(default_factory=list)
    errors: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)


class TemplateRegistry:
    """模板注册表 - 管理可用的项目模板"""

    def __init__(
        self,
        template_dir: str,
        fs: FileSystemProtocol,
    ) -> None:
        self._template_dir = template_dir
        self._fs = fs
        self._templates: Dict[str, TemplateInfo] = {}
        self._load_templates()

    def _load_templates(self) -> None:
        try:
            template_files = self._fs.list_dir(self._template_dir)
            for entry in template_files:
                if entry.endswith("/"):
                    template_name = entry.rstrip("/")
                    self._load_template(template_name)
        except Exception as e:
            self._templates = {}

    def _load_template(self, name: str) -> None:
        meta_path = os.path.join(self._template_dir, name, "template.json")
        try:
            if self._fs.exists(meta_path):
                meta_content = self._fs.read_file(meta_path)
                import json
                meta = json.loads(meta_content)
                self._templates[name] = TemplateInfo(
                    name=name,
                    description=meta.get("description", ""),
                    project_type=meta.get("project_type", "service"),
                    language=meta.get("language", "python"),
                    path=os.path.join(self._template_dir, name),
                    parameters=meta.get("parameters", []),
                )
        except Exception:
            self._templates[name] = TemplateInfo(
                name=name,
                description=f"Template: {name}",
                project_type="service",
                language="python",
                path=os.path.join(self._template_dir, name),
            )

    def list_templates(self) -> List[TemplateInfo]:
        return list(self._templates.values())

    def get_template(self, name: str) -> Optional[TemplateInfo]:
        return self._templates.get(name)

    def search_templates(
        self,
        project_type: Optional[str] = None,
        language: Optional[str] = None,
    ) -> List[TemplateInfo]:
        results = list(self._templates.values())
        if project_type:
            results = [t for t in results if t.project_type == project_type]
        if language:
            results = [t for t in results if t.language == language]
        return results


class InteractivePrompter:
    """交互式问答提示器 - 参数化配置收集"""

    def __init__(
        self,
        input_fn: Optional[Callable[[str], str]] = None,
        logger: Optional[LoggerProtocol] = None,
    ) -> None:
        self._input = input_fn or input
        self._logger = logger

    def prompt(self, message: str, default: Optional[str] = None) -> str:
        prompt_msg = message
        if default is not None:
            prompt_msg = f"{message} [{default}]: "
        else:
            prompt_msg = f"{message}: "

        result = self._input(prompt_msg).strip()
        if not result and default is not None:
            return default
        return result

    def prompt_choice(
        self,
        message: str,
        choices: List[str],
        default: Optional[int] = None,
    ) -> str:
        for i, choice in enumerate(choices, 1):
            print(f"  {i}. {choice}")

        while True:
            default_str = f" [{default}]" if default is not None else ""
            result = self._input(f"{message}{default_str}: ").strip()

            if not result and default is not None:
                return choices[default - 1]

            try:
                idx = int(result) - 1
                if 0 <= idx < len(choices):
                    return choices[idx]
            except ValueError:
                if result in choices:
                    return result

            print(f"Invalid choice, please select 1-{len(choices)}")

    def prompt_yes_no(self, message: str, default: bool = True) -> bool:
        default_str = "Y/n" if default else "y/N"
        while True:
            result = self._input(f"{message} [{default_str}]: ").strip().lower()
            if not result:
                return default
            if result in ["y", "yes"]:
                return True
            if result in ["n", "no"]:
                return False

    def collect_parameters(
        self,
        parameters: List[Dict[str, Any]],
    ) -> Dict[str, Any]:
        """根据模板定义的参数列表进行交互式收集"""
        results: Dict[str, Any] = {}
        for param in parameters:
            name = param.get("name", "")
            description = param.get("description", name)
            param_type = param.get("type", "string")
            default = param.get("default")
            choices = param.get("choices")

            if choices:
                results[name] = self.prompt_choice(
                    description,
                    choices,
                    choices.index(default) + 1 if default in choices else None,
                )
            elif param_type == "boolean":
                results[name] = self.prompt_yes_no(description, bool(default))
            else:
                results[name] = self.prompt(description, str(default) if default else None)

        return results


class ProjectScaffold:
    """
    项目脚手架生成器
    只依赖抽象协议，不依赖具体实现，可独立测试
    """

    def __init__(
        self,
        template_engine: TemplateEngineProtocol,
        file_system: FileSystemProtocol,
        template_registry: TemplateRegistry,
        logger: Optional[LoggerProtocol] = None,
    ) -> None:
        self._template_engine = template_engine
        self._fs = file_system
        self._registry = template_registry
        self._logger = logger

    def _log(self, level: str, message: str, **kwargs: Any) -> None:
        if self._logger:
            log_method = getattr(self._logger, level, self._logger.info)
            log_method(message, **kwargs)

    def _should_ignore(self, filename: str, ignore_patterns: List[str]) -> bool:
        for pattern in ignore_patterns:
            if fnmatch.fnmatch(filename, pattern):
                return True
        return False

    def _collect_template_files(
        self,
        template_path: str,
        ignore_patterns: List[str],
    ) -> List[str]:
        files = []
        self._collect_files_recursive(template_path, "", files, ignore_patterns)
        return files

    def _collect_files_recursive(
        self,
        base_path: str,
        current_path: str,
        files: List[str],
        ignore_patterns: List[str],
    ) -> None:
        full_path = os.path.join(base_path, current_path) if current_path else base_path
        entries = self._fs.list_dir(full_path)

        for entry in entries:
            is_dir = entry.endswith("/")
            entry_name = entry.rstrip("/")
            rel_entry = os.path.join(current_path, entry_name) if current_path else entry_name

            if self._should_ignore(entry_name, ignore_patterns):
                continue

            if is_dir:
                self._collect_files_recursive(base_path, rel_entry, files, ignore_patterns)
            else:
                if not entry_name == "template.json":
                    files.append(rel_entry)

    def _render_filename(self, filename: str, context: Dict[str, Any]) -> str:
        import re
        def replace_var(match):
            var_name = match.group(1).strip()
            return str(context.get(var_name, match.group(0)))
        return re.sub(r"\{\{\s*(\w+)\s*\}\}", replace_var, filename)

    async def generate(
        self,
        config: ScaffoldConfig,
        interactive: bool = False,
    ) -> ScaffoldResult:
        """
        根据配置生成项目骨架

        Args:
            config: 脚手架配置
            interactive: 是否使用交互式问答补全参数

        Returns:
            ScaffoldResult: 生成结果
        """
        result = ScaffoldResult(
            success=False,
            project_path=config.output_dir or config.project_name,
        )

        try:
            template_info = self._registry.get_template(config.template)
            if not template_info:
                result.errors.append(f"Template not found: {config.template}")
                return result

            if interactive:
                prompter = InteractivePrompter(logger=self._logger)
                collected_params = prompter.collect_parameters(template_info.parameters)
                config.parameters.update(collected_params)

            context = {
                "project_name": config.project_name,
                "project_type": config.project_type,
                "language": config.language,
                "author": config.author,
                **config.parameters,
            }

            self._log("info", f"Generating project: {config.project_name}", template=config.template)

            ignore_patterns = ["__pycache__", "*.pyc", ".git", "node_modules"]
            template_files = self._collect_template_files(template_info.path, ignore_patterns)

            output_base = config.output_dir or config.project_name

            for rel_path in template_files:
                try:
                    template_file = os.path.join(template_info.path, rel_path)
                    template_content = self._fs.read_file(template_file)

                    rendered_content = self._template_engine.render_string(
                        template_content, context
                    )

                    output_rel_path = self._render_filename(rel_path, context)
                    output_path = os.path.join(output_base, output_rel_path)

                    self._fs.write_file(output_path, rendered_content)
                    result.created_files.append(output_path)

                    self._log("debug", f"Created file: {output_path}")

                except TemplateError as e:
                    result.warnings.append(f"Template error in {rel_path}: {e}")
                except Exception as e:
                    result.errors.append(f"Failed to process {rel_path}: {e}")

            if result.errors:
                result.success = False
                self._log("error", "Project generation failed", errors=result.errors)
            else:
                result.success = True
                self._log(
                    "info",
                    f"Project generated successfully: {config.project_name}",
                    files_count=len(result.created_files),
                )

        except ScaffoldError:
            raise
        except Exception as e:
            result.errors.append(f"Generation failed: {e}")
            self._log("error", "Project generation failed", error=str(e))

        return result

    def generate_from_interactive(self) -> ScaffoldResult:
        """交互式生成项目 - 通过问答收集所有配置"""
        prompter = InteractivePrompter(logger=self._logger)

        print("\n=== Project Scaffold Generator ===")

        templates = self._registry.list_templates()
        template_names = [t.name for t in templates]
        if not template_names:
            return ScaffoldResult(
                success=False,
                project_path="",
                errors=["No templates available"],
            )

        template_choice = prompter.prompt_choice(
            "Select project template",
            template_names,
            default=1,
        )

        template_info = self._registry.get_template(template_choice)

        project_name = prompter.prompt("Project name", "my-project")
        author = prompter.prompt("Author", "anonymous")
        output_dir = prompter.prompt("Output directory", project_name)

        config = ScaffoldConfig(
            project_name=project_name,
            project_type=template_info.project_type if template_info else "service",
            language=template_info.language if template_info else "python",
            author=author,
            template=template_choice,
            output_dir=output_dir,
        )

        return self.generate(config, interactive=True)
