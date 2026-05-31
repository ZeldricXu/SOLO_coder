import tempfile
from pathlib import Path

import pytest

from app.scaffold.generator import (
    TemplateEngine,
    ProjectGenerator,
    generate_project
)


@pytest.fixture
def template_engine():
    return TemplateEngine()


def test_snake_case(template_engine):
    assert template_engine.to_snake_case("MyProject") == "my_project"
    assert template_engine.to_snake_case("my-project") == "my_project"
    assert template_engine.to_snake_case("MyAPI") == "my_api"


def test_camel_case(template_engine):
    assert template_engine.to_camel_case("my_project") == "MyProject"
    assert template_engine.to_camel_case("my-project") == "MyProject"
    assert template_engine.to_camel_case("myAPi") == "MyApi"


def test_kebab_case(template_engine):
    assert template_engine.to_kebab_case("MyProject") == "my-project"
    assert template_engine.to_kebab_case("my_project") == "my-project"


def test_render_string(template_engine):
    result = template_engine.render_string(
        "Hello {{ name }}!",
        {"name": "World"}
    )
    assert result == "Hello World!"


def test_render_string_with_helpers(template_engine):
    result = template_engine.render_string(
        "Package: {{ project_name | snake_case }}",
        {"project_name": "MyProject"}
    )
    assert result == "Package: my_project"


def test_project_generator(temp_dir):
    generator = ProjectGenerator()

    template = {
        "files": [
            {
                "name": "README.md",
                "content": "# {{ project_name }}\n\n{{ description }}"
            },
            {
                "name": "src/{{ package_name }}/__init__.py",
                "content": '__version__ = "{{ version }}"'
            }
        ]
    }

    variables = {
        "project_name": "TestProject",
        "package_name": "testproject",
        "description": "A test project",
        "version": "1.0.0"
    }

    result = generator.generate(
        template=template,
        output_dir=str(temp_dir),
        variables=variables
    )

    assert result["output_dir"] == str(temp_dir)
    assert result["files_generated"] == 2

    readme = temp_dir / "README.md"
    assert readme.exists()
    assert readme.read_text() == "# TestProject\n\nA test project"

    init_file = temp_dir / "src" / "testproject" / "__init__.py"
    assert init_file.exists()
    assert init_file.read_text() == '__version__ = "1.0.0"'


def test_project_generator_skip_existing(temp_dir):
    generator = ProjectGenerator()

    template = {
        "files": [
            {
                "name": "file.txt",
                "content": "content"
            }
        ]
    }

    existing = temp_dir / "file.txt"
    existing.write_text("existing")

    result = generator.generate(
        template=template,
        output_dir=str(temp_dir),
        variables={},
        skip_existing=True
    )

    assert result["files_generated"] == 0
    assert existing.read_text() == "existing"


def test_get_builtin_templates():
    generator = ProjectGenerator()
    templates = generator.list_templates()

    assert "fastapi" in templates
    assert "celery-worker" in templates


def test_get_builtin_template():
    generator = ProjectGenerator()
    template = generator.get_template("fastapi")

    assert template is not None
    assert "description" in template
    assert "variables" in template
    assert "files" in template


def test_generate_project_function(temp_dir):
    result = generate_project(
        template_name="fastapi",
        output_dir=str(temp_dir),
        variables={
            "project_name": "MyProject",
            "description": "Test",
            "author": "Test Author"
        },
        skip_existing=False
    )

    assert result["files_generated"] > 0
    assert (temp_dir / "requirements.txt").exists()
    assert (temp_dir / "main.py").exists()


def test_invalid_template():
    with pytest.raises(ValueError):
        generate_project(
            template_name="nonexistent",
            output_dir="/tmp/test",
            variables={}
        )
