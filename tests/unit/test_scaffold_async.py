"""
单元测试: 项目脚手架 - 异步执行回调通知
"""

import pytest
import asyncio
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "src"))

from src.domain.models.common import ScaffoldConfig
from src.infra.template import Jinja2TemplateEngine, InMemoryFileSystem
from src.modules.scaffold import (
    ProjectScaffold,
    TemplateRegistry,
    ScaffoldHook,
    SimpleHook,
    TaskStatus,
)


@pytest.fixture
def memory_fs():
    fs = InMemoryFileSystem()
    fs.create_dir("templates/python-service")
    fs.write_file(
        "templates/python-service/template.json",
        '{"name": "Python Service", "description": "Test", "project_type": "service", "language": "python", "parameters": []}',
    )
    fs.write_file(
        "templates/python-service/README.md",
        "# {{ project_name }}\n\nAuthor: {{ author }}"
    )
    fs.write_file(
        "templates/python-service/src/main.py",
        'app = FastAPI(title="{{ project_name }}")'
    )
    return fs


@pytest.fixture
def template_engine():
    return Jinja2TemplateEngine()


@pytest.fixture
def template_registry(memory_fs):
    return TemplateRegistry("templates", memory_fs)


@pytest.fixture
def scaffold(memory_fs, template_engine, template_registry):
    return ProjectScaffold(
        template_engine=template_engine,
        file_system=memory_fs,
        template_registry=template_registry,
    )


class TestScaffoldHooks:
    def test_add_hook(self, scaffold):
        """测试添加钩子"""
        hook = SimpleHook()
        scaffold.add_hook(hook)
        assert len(scaffold._hooks) == 1
        assert hook in scaffold._hooks

    def test_remove_hook(self, scaffold):
        """测试移除钩子"""
        hook = SimpleHook()
        scaffold.add_hook(hook)
        scaffold.remove_hook(hook)
        assert hook not in scaffold._hooks

    def test_add_simple_hook(self, scaffold):
        """测试添加简单函数钩子"""
        called = []

        def before(config):
            called.append(config.project_name)

        hook = scaffold.add_simple_hook(before=before)
        assert hook in scaffold._hooks

    @pytest.mark.asyncio
    async def test_before_hook_called(self, scaffold):
        """测试before钩子被调用"""
        called = []

        async def before(config):
            called.append(config.project_name)

        scaffold.add_simple_hook(before=before)

        config = ScaffoldConfig(
            project_name="test-project",
            project_type="service",
            language="python",
            author="Test Author",
            template="python-service",
            output_dir="output/test",
        )
        await scaffold.generate(config)

        assert "test-project" in called

    @pytest.mark.asyncio
    async def test_success_hook_called(self, scaffold):
        """测试success钩子被调用"""
        results = []

        async def success(config, result):
            results.append(result.success)

        scaffold.add_simple_hook(success=success)

        config = ScaffoldConfig(
            project_name="test",
            project_type="service",
            language="python",
            author="Test",
            template="python-service",
            output_dir="output/test",
        )
        await scaffold.generate(config)

        assert len(results) == 1
        assert results[0] is True

    @pytest.mark.asyncio
    async def test_failure_hook_called(self, scaffold):
        """测试failure钩子被调用"""
        errors = []

        async def failure(config, error):
            errors.append(error)

        scaffold.add_simple_hook(failure=failure)

        config = ScaffoldConfig(
            project_name="test",
            project_type="service",
            language="python",
            author="Test",
            template="nonexistent",
            output_dir="output/test",
        )
        await scaffold.generate(config)

        assert len(errors) == 1


class TestScaffoldAsync:
    @pytest.mark.asyncio
    async def test_generate_async_returns_task_id(self, scaffold):
        """测试异步生成返回任务ID"""
        config = ScaffoldConfig(
            project_name="test",
            project_type="service",
            language="python",
            author="Test",
            template="python-service",
            output_dir="output/test",
        )
        task_id = await scaffold.generate_async(config)
        assert task_id is not None
        assert len(task_id) > 0

    @pytest.mark.asyncio
    async def test_get_task(self, scaffold):
        """测试获取任务"""
        config = ScaffoldConfig(
            project_name="test",
            project_type="service",
            language="python",
            author="Test",
            template="python-service",
            output_dir="output/test",
        )
        task_id = await scaffold.generate_async(config)

        task = scaffold.get_task(task_id)
        assert task is not None
        assert task.task_id == task_id

    @pytest.mark.asyncio
    async def test_task_completes(self, scaffold):
        """测试任务完成"""
        config = ScaffoldConfig(
            project_name="test",
            project_type="service",
            language="python",
            author="Test",
            template="python-service",
            output_dir="output/test",
        )
        task_id = await scaffold.generate_async(config)

        await asyncio.sleep(0.1)

        task = scaffold.get_task(task_id)
        assert task.status in [TaskStatus.RUNNING, TaskStatus.COMPLETED]

    @pytest.mark.asyncio
    async def test_list_tasks(self, scaffold):
        """测试列出任务"""
        config1 = ScaffoldConfig(
            project_name="test1",
            project_type="service",
            language="python",
            author="Test",
            template="python-service",
            output_dir="output/test1",
        )
        config2 = ScaffoldConfig(
            project_name="test2",
            project_type="service",
            language="python",
            author="Test",
            template="python-service",
            output_dir="output/test2",
        )

        await scaffold.generate_async(config1)
        await scaffold.generate_async(config2)

        tasks = scaffold.list_tasks()
        assert len(tasks) == 2

    @pytest.mark.asyncio
    async def test_list_tasks_by_status(self, scaffold):
        """测试按状态列出任务"""
        config = ScaffoldConfig(
            project_name="test",
            project_type="service",
            language="python",
            author="Test",
            template="python-service",
            output_dir="output/test",
        )
        task_id = await scaffold.generate_async(config)

        await asyncio.sleep(0.1)

        pending = scaffold.list_tasks(status=TaskStatus.PENDING)
        assert len(pending) == 0

    def test_task_status_enum(self):
        """测试任务状态枚举"""
        assert TaskStatus.PENDING.value == "pending"
        assert TaskStatus.RUNNING.value == "running"
        assert TaskStatus.COMPLETED.value == "completed"
        assert TaskStatus.FAILED.value == "failed"
        assert TaskStatus.CANCELLED.value == "cancelled"
