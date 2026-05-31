"""
单元测试: 脚手架模块 - 使用InMemoryFileSystem独立测试
"""

import pytest
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "src"))

from src.domain.models.common import ScaffoldConfig
from src.infra.template import Jinja2TemplateEngine, InMemoryFileSystem
from src.modules.scaffold import ProjectScaffold, TemplateRegistry


@pytest.fixture
def memory_fs():
    fs = InMemoryFileSystem()
    fs.create_dir("templates/python-service")
    fs.write_file(
        "templates/python-service/template.json",
        '{"name": "Python Service", "description": "Test", "project_type": "service", "language": "python", "parameters": []}',
    )
    fs.write_file("templates/python-service/README.md", "# {{ project_name }}\n\nAuthor: {{ author }}")
    fs.write_file("templates/python-service/src/main.py", 'app = FastAPI(title="{{ project_name }}")')
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
    config = ScaffoldConfig(
        project_name="test-service",
        project_type="service",
        language="python",
        author="Test Author",
        template="python-service",
        output_dir="output/test-service",
    )
    result = await scaffold.generate(config)
    assert result.success is True
    assert len(result.created_files) == 2
    assert len(result.errors) == 0

    readme = memory_fs.read_file("output/test-service/README.md")
    assert "# test-service" in readme
    assert "Author: Test Author" in readme


@pytest.mark.asyncio
async def test_scaffold_template_not_found(scaffold):
    config = ScaffoldConfig(
        project_name="test", project_type="service", language="python",
        author="Test", template="nonexistent", output_dir="output/test",
    )
    result = await scaffold.generate(config)
    assert result.success is False
    assert len(result.errors) >= 1


def test_template_registry_list_templates(template_registry):
    templates = template_registry.list_templates()
    assert len(templates) == 1
    assert templates[0].name == "python-service"


def test_template_registry_search(template_registry):
    results = template_registry.search_templates(language="python")
    assert len(results) == 1
    results = template_registry.search_templates(language="java")
    assert len(results) == 0
