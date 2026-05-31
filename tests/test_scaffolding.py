import pytest
import sys
import os
import shutil
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from modules.scaffolding_module import get_scaffolder, ProjectType, TemplateConfig


def test_list_templates():
    scaffolder = get_scaffolder()
    templates = scaffolder.list_templates()
    assert "fastapi" in templates
    assert "cli" in templates
    assert "library" in templates


def test_generate_fastapi_project():
    scaffolder = get_scaffolder()
    config = TemplateConfig(
        name="test-fastapi-project",
        template_type=ProjectType.FASTAPI,
        variables={},
        author="Test Author",
        version="1.0.0",
        description="Test FastAPI project",
    )
    project = scaffolder.generate(config)
    assert project is not None
    assert project.project_path == "test-fastapi-project"
    assert project.template_type == ProjectType.FASTAPI
    assert len(project.files) > 0
    assert any(f.path == "main.py" for f in project.files)
    assert any(f.path == "requirements.txt" for f in project.files)


def test_generate_cli_project():
    scaffolder = get_scaffolder()
    config = TemplateConfig(
        name="test-cli",
        template_type=ProjectType.CLI,
        author="Test",
        description="Test CLI project",
    )
    project = scaffolder.generate(config)
    assert project is not None
    assert any(f.path == "main.py" for f in project.files)


def test_generate_library_project():
    scaffolder = get_scaffolder()
    config = TemplateConfig(
        name="test-lib",
        template_type=ProjectType.LIBRARY,
        author="Test",
        description="Test library",
    )
    project = scaffolder.generate(config)
    assert project is not None
    assert any(f.path == "src/__init__.py" for f in project.files)


def test_variable_replacement():
    scaffolder = get_scaffolder()
    config = TemplateConfig(
        name="my-project",
        template_type=ProjectType.FASTAPI,
        author="Custom Author",
        version="2.0.0",
        description="Custom description",
    )
    project = scaffolder.generate(config)
    main_file = next(f for f in project.files if f.path == "main.py")
    assert "my-project" in main_file.content


def test_write_to_disk():
    scaffolder = get_scaffolder()
    config = TemplateConfig(
        name="disk-test",
        template_type=ProjectType.FASTAPI,
    )
    project = scaffolder.generate(config)

    with tempfile.TemporaryDirectory() as tmpdir:
        path = scaffolder.write_to_disk(project, tmpdir)
        assert os.path.exists(path)
        assert os.path.exists(os.path.join(path, "main.py"))
        assert os.path.exists(os.path.join(path, "requirements.txt"))


def test_invalid_template_type():
    scaffolder = get_scaffolder()
    config = TemplateConfig(
        name="test",
        template_type="invalid",
    )
    with pytest.raises(ValueError):
        scaffolder.generate(config)
