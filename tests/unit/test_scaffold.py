"""
单元测试: 项目脚手架模块
展示如何使用 InMemoryFileSystem 进行单元测试，无需依赖真实文件系统
"""

import pytest
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "src"))

from src.core import ScaffoldConfig
from src.infrastructure.template import (
    Jinja2TemplateEngine,
    InMemoryFileSystem,
)
from src.modules.scaffold import ProjectScaffold, TemplateRegistry


@pytest.fixture
def memory_fs():
    """内存文件系统 - 用于测试"""
    fs = InMemoryFileSystem()

    fs.create_dir("templates/python-service")
    fs.write_file(
        "templates/python-service/template.json",
        """{
        "name": "Python Service",
        "description": "Test template",
        "project_type": "service",
        "language": "python",
        "parameters": [
            {"name": "use_docker", "description": "Use Docker", "type": "boolean", "default": true}
        ]
    }"""
    )
    fs.write_file(
        "templates/python-service/README.md",
        "# {{ project_name }}\n\nAuthor: {{ author }}"
    )
    fs.write_file(
        "templates/python-service/src/main.py",
        "app = FastAPI(title=\"{{ project_name }}\")"
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


@pytest.mark.asyncio
async def test_scaffold_generate_success(scaffold, memory_fs):
    """测试成功生成项目"""
    config = ScaffoldConfig(
        project_name="test-service",
        project_type="service",
        language="python",
        author="Test Author",
        template="python-service",
        output_dir="output/test-service",
        parameters={"use_docker": True},
    )

    result = await scaffold.generate(config)

    assert result.success is True
    assert len(result.created_files) == 2
    assert len(result.errors) == 0

    files = memory_fs.get_all_files()
    assert "output/test-service/README.md" in files
    assert "output/test-service/src/main.py" in files

    readme = memory_fs.read_file("output/test-service/README.md")
    assert "# test-service" in readme
    assert "Author: Test Author" in readme

    main_py = memory_fs.read_file("output/test-service/src/main.py")
    assert 'FastAPI(title="test-service")' in main_py


@pytest.mark.asyncio
async def test_scaffold_template_not_found(scaffold):
    """测试模板不存在"""
    config = ScaffoldConfig(
        project_name="test-service",
        project_type="service",
        language="python",
        author="Test Author",
        template="nonexistent-template",
        output_dir="output/test",
    )

    result = await scaffold.generate(config)

    assert result.success is False
    assert len(result.errors) >= 1
    assert "Template not found" in result.errors[0]


@pytest.mark.asyncio
async def test_scaffold_with_filename_templating(scaffold, memory_fs):
    """测试文件名中的变量替换"""
    memory_fs.write_file(
        "templates/python-service/{{project_name}}.txt",
        "File for {{ project_name }}"
    )

    config = ScaffoldConfig(
        project_name="myapp",
        project_type="service",
        language="python",
        author="Test",
        template="python-service",
        output_dir="output",
    )

    result = await scaffold.generate(config)

    assert result.success is True
    files = memory_fs.get_all_files()
    assert "output/myapp.txt" in files

    content = memory_fs.read_file("output/myapp.txt")
    assert "File for myapp" in content


def test_template_registry_list_templates(template_registry):
    """测试模板注册表"""
    templates = template_registry.list_templates()
    assert len(templates) == 1
    assert templates[0].name == "python-service"
    assert templates[0].language == "python"


def test_template_registry_search(template_registry):
    """测试模板搜索"""
    results = template_registry.search_templates(language="python")
    assert len(results) == 1

    results = template_registry.search_templates(language="java")
    assert len(results) == 0
