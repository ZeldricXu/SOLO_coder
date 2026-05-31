"""
Comprehensive tests for the Project Scaffolder module.

Test Matrix:
  - Normal Path: Template loading, variable validation, project generation
  - Boundary Inputs: Empty templates, edge case variables, large projects
  - Concurrent Operations: Multiple concurrent generations
  - Exception Injection: Invalid templates, missing variables, write errors
"""
from __future__ import annotations

import asyncio
import json
import os
import tempfile
import time
from pathlib import Path
from typing import Any, Dict, List, Optional
from unittest.mock import AsyncMock, MagicMock, PropertyMock, patch

import pytest
import yaml

from src.scaffolder.scaffolder import (
    GeneratedFile,
    InteractivePrompter,
    ProjectScaffolder,
    Template,
    TemplateManager,
    TemplateVariable,
)
from src.utils.errors import ConfigurationError, ValidationError

from tests.builders import (
    GeneratedFileBuilder,
    TemplateBuilder,
    TemplateVariableBuilder,
    create_template_variables_dict,
)


# ============================================================================
# TemplateVariable Tests
# ============================================================================


class TestTemplateVariable:
    """Tests for TemplateVariable dataclass."""

    def test_default_creation(self):
        var = TemplateVariable(
            name="project_name",
            type="string",
            description="Project name",
            default="my-project",
            required=True,
        )
        assert var.name == "project_name"
        assert var.type == "string"
        assert var.required is True

    def test_minimal_creation(self):
        var = TemplateVariable(name="test_var")
        assert var.name == "test_var"
        assert var.type == "string"
        assert var.required is True

    def test_with_choices(self):
        var = TemplateVariable(
            name="license",
            choices=["MIT", "Apache-2.0", "GPL-3.0"],
        )
        assert len(var.choices) == 3

    def test_with_validation(self):
        var = TemplateVariable(
            name="project_name",
            validation=r"^[a-z][a-z0-9-]+$",
        )
        assert var.validation is not None


# ============================================================================
# Template Tests
# ============================================================================


class TestTemplate:
    """Tests for Template dataclass."""

    def test_default_creation(self):
        var = TemplateVariableBuilder().with_name("name").build()
        template = Template(
            template_id="tpl_001",
            name="Test Template",
            description="A test template",
            version="1.0.0",
            variables=[var],
            tags=["test"],
        )
        assert template.template_id == "tpl_001"
        assert len(template.variables) == 1

    def test_minimal_creation(self):
        template = Template(
            template_id="tpl_minimal",
            name="Minimal",
            description="Minimal template",
        )
        assert template.variables == []
        assert template.tags == []
        assert template.version == "1.0.0"


# ============================================================================
# TemplateManager Unit Tests
# ============================================================================


class TestTemplateManager:
    """Unit tests for TemplateManager."""

    # --- Type Conversion Tests ---

    def test_convert_boolean_true_values(self):
        assert TemplateManager._convert_boolean(True) is True
        assert TemplateManager._convert_boolean("true") is True
        assert TemplateManager._convert_boolean("True") is True
        assert TemplateManager._convert_boolean("1") is True
        assert TemplateManager._convert_boolean("yes") is True
        assert TemplateManager._convert_boolean("YES") is True

    def test_convert_boolean_false_values(self):
        assert TemplateManager._convert_boolean(False) is False
        assert TemplateManager._convert_boolean("false") is False
        assert TemplateManager._convert_boolean("False") is False
        assert TemplateManager._convert_boolean("0") is False
        assert TemplateManager._convert_boolean("no") is False

    def test_convert_boolean_edge_cases(self):
        assert TemplateManager._convert_boolean("random") is False
        assert TemplateManager._convert_boolean("") is False
        assert TemplateManager._convert_boolean(0) is False

    def test_convert_number_integer(self):
        result = TemplateManager._convert_number("42")
        assert result == 42
        assert isinstance(result, int)

    def test_convert_number_float(self):
        result = TemplateManager._convert_number("3.14")
        assert result == pytest.approx(3.14)
        assert isinstance(result, float)

    def test_convert_number_zero(self):
        assert TemplateManager._convert_number("0") == 0
        assert TemplateManager._convert_number("0.0") == 0.0

    def test_convert_number_negative(self):
        assert TemplateManager._convert_number("-42") == -42
        assert TemplateManager._convert_number("-3.14") == pytest.approx(-3.14)

    def test_convert_number_invalid(self):
        with pytest.raises(ValueError):
            TemplateManager._convert_number("not_a_number")

    # --- Validation Tests ---

    def test_validate_choices_valid(self):
        assert TemplateManager._validate_choices("a", ["a", "b", "c"]) is True

    def test_validate_choices_invalid(self):
        assert TemplateManager._validate_choices("d", ["a", "b", "c"]) is False

    def test_validate_choices_empty(self):
        assert TemplateManager._validate_choices("a", []) is False

    def test_validate_format_valid(self):
        assert TemplateManager._validate_format("test123", r"^[a-z0-9]+$") is True

    def test_validate_format_invalid(self):
        assert TemplateManager._validate_format("TEST@123", r"^[a-z0-9]+$") is False

    def test_validate_format_empty_string(self):
        assert TemplateManager._validate_format("", r"^$") is True
        assert TemplateManager._validate_format("", r"^[a-z]+$") is False

    # --- Template Loading Tests ---

    def test_builtin_templates_loaded(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            manager = TemplateManager(templates_dir=tmpdir)
            templates = manager.list_templates()
            assert len(templates) >= 4

    def test_get_existing_template(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            manager = TemplateManager(templates_dir=tmpdir)
            template = manager.get_template("python-service")
            assert template is not None
            assert template.template_id == "python-service"

    def test_get_nonexistent_template(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            manager = TemplateManager(templates_dir=tmpdir)
            template = manager.get_template("nonexistent")
            assert template is None

    def test_list_templates_with_tag_filter(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            manager = TemplateManager(templates_dir=tmpdir)
            templates = manager.list_templates(tags=["python"])
            assert len(templates) >= 3
            for t in templates:
                assert "python" in t.tags

    def test_list_templates_empty_tag_filter(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            manager = TemplateManager(templates_dir=tmpdir)
            all_templates = manager.list_templates()
            filtered = manager.list_templates(tags=[])
            assert len(all_templates) == len(filtered)

    # --- Template Registration Tests ---

    def test_register_custom_template(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            manager = TemplateManager(templates_dir=tmpdir)

            template = TemplateBuilder() \
                .with_id("custom-template") \
                .with_name("Custom Template") \
                .with_description("A custom template") \
                .add_variable(
                    TemplateVariableBuilder()
                    .with_name("name")
                    .with_description("Project name")
                    .build()
                ) \
                .with_directory("custom") \
                .build()

            template_files = {
                "README.md": "# {{ name }}",
                "setup.py": "print('{{ name }}')",
            }

            manager.register_template(template, template_files)

            retrieved = manager.get_template("custom-template")
            assert retrieved is not None
            assert retrieved.name == "Custom Template"

            template_dir = Path(tmpdir) / "custom"
            assert (template_dir / "README.md").exists()
            assert (template_dir / "setup.py").exists()

    def test_register_template_with_nested_files(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            manager = TemplateManager(templates_dir=tmpdir)

            template = TemplateBuilder() \
                .with_id("nested-template") \
                .with_name("Nested Template") \
                .with_description("Template with nested files") \
                .with_directory("nested") \
                .build()

            template_files = {
                "src/main.py": "print('hello')",
                "src/utils/helpers.py": "def helper(): pass",
                "tests/test_main.py": "def test_main(): pass",
            }

            manager.register_template(template, template_files)

            template_dir = Path(tmpdir) / "nested"
            assert (template_dir / "src" / "main.py").exists()
            assert (template_dir / "src" / "utils" / "helpers.py").exists()
            assert (template_dir / "tests" / "test_main.py").exists()

    # --- Variable Validation Tests ---

    def test_validate_variables_all_required_present(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            manager = TemplateManager(templates_dir=tmpdir)
            template = manager.get_template("python-service")

            variables = create_template_variables_dict()
            is_valid, errors = manager.validate_variables(template, variables)
            assert is_valid is True
            assert errors == []

    def test_validate_variables_missing_required(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            manager = TemplateManager(templates_dir=tmpdir)
            template = manager.get_template("python-service")

            variables = {}
            is_valid, errors = manager.validate_variables(template, variables)
            assert is_valid is False
            assert any("project_name" in e for e in errors)

    def test_validate_variables_invalid_choice(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            manager = TemplateManager(templates_dir=tmpdir)
            template = manager.get_template("python-service")

            variables = create_template_variables_dict()
            variables["python_version"] = "2.7"
            is_valid, errors = manager.validate_variables(template, variables)
            assert is_valid is False
            assert any("python_version" in e for e in errors)

    def test_validate_variables_invalid_format(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            manager = TemplateManager(templates_dir=tmpdir)
            template = manager.get_template("python-service")

            variables = create_template_variables_dict()
            variables["project_name"] = "INVALID-NAME"
            is_valid, errors = manager.validate_variables(template, variables)
            assert is_valid is False
            assert any("project_name" in e for e in errors)

    def test_validate_variables_boolean_conversion(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            manager = TemplateManager(templates_dir=tmpdir)
            template = manager.get_template("python-service")

            variables = create_template_variables_dict()
            variables["use_database"] = "true"

            is_valid, errors = manager.validate_variables(template, variables)
            assert is_valid is True
            assert variables["use_database"] is True

    def test_validate_variables_optional_with_default(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            manager = TemplateManager(templates_dir=tmpdir)
            template = manager.get_template("python-service")

            variables = {"project_name": "test-project"}
            is_valid, errors = manager.validate_variables(template, variables)
            assert is_valid is True
            assert variables["author"] == "Developer"


# ============================================================================
# InteractivePrompter Tests
# ============================================================================


class TestInteractivePrompter:
    """Tests for InteractivePrompter with mocked input."""

    def test_initialization(self):
        prompter = InteractivePrompter(use_colors=True)
        assert prompter.use_colors is True

    def test_colorize_with_colors(self):
        prompter = InteractivePrompter(use_colors=True)
        result = prompter._colorize("test", "cyan")
        assert "test" in result
        assert "\033[" in result

    def test_colorize_without_colors(self):
        prompter = InteractivePrompter(use_colors=False)
        result = prompter._colorize("test", "cyan")
        assert result == "test"
        assert "\033[" not in result

    def test_format_prompt_simple(self):
        prompter = InteractivePrompter(use_colors=False)
        result = prompter._format_prompt("Enter name")
        assert result == "Enter name: "

    def test_format_prompt_with_default(self):
        prompter = InteractivePrompter(use_colors=False)
        result = prompter._format_prompt("Enter name", default="John")
        assert result == "Enter name [John]: "

    def test_format_prompt_with_choices(self):
        prompter = InteractivePrompter(use_colors=False)
        result = prompter._format_prompt("Select", choices=["a", "b", "c"])
        assert result == "Select (a, b, c): "

    def test_prompt_with_user_input(self):
        prompter = InteractivePrompter(use_colors=False)
        prompter.input_fn = lambda prompt: "John"

        result = prompter.prompt("Enter name")
        assert result == "John"

    def test_prompt_with_default_value(self):
        prompter = InteractivePrompter(use_colors=False)
        prompter.input_fn = lambda prompt: ""

        result = prompter.prompt("Enter name", default="Default")
        assert result == "Default"

    def test_prompt_required_retries_on_empty(self):
        prompter = InteractivePrompter(use_colors=False)
        calls = ["", "John"]
        prompter.input_fn = lambda prompt: calls.pop(0)

        result = prompter.prompt("Enter name", required=True)
        assert result == "John"

    def test_prompt_with_choices_valid(self):
        prompter = InteractivePrompter(use_colors=False)
        prompter.input_fn = lambda prompt: "a"

        result = prompter.prompt("Select", choices=["a", "b", "c"])
        assert result == "a"

    def test_prompt_with_choices_invalid_retries(self):
        prompter = InteractivePrompter(use_colors=False)
        calls = ["d", "a"]
        prompter.input_fn = lambda prompt: calls.pop(0)

        result = prompter.prompt("Select", choices=["a", "b", "c"])
        assert result == "a"

    def test_prompt_with_validation_valid(self):
        prompter = InteractivePrompter(use_colors=False)
        prompter.input_fn = lambda prompt: "test123"

        result = prompter.prompt("Name", validation=r"^[a-z0-9]+$")
        assert result == "test123"

    def test_prompt_with_validation_invalid_retries(self):
        prompter = InteractivePrompter(use_colors=False)
        calls = ["INVALID", "valid123"]
        prompter.input_fn = lambda prompt: calls.pop(0)

        result = prompter.prompt("Name", validation=r"^[a-z0-9]+$")
        assert result == "valid123"

    def test_prompt_confirm_yes(self):
        prompter = InteractivePrompter(use_colors=False)
        prompter.input_fn = lambda prompt: "y"

        result = prompter.prompt_confirm("Proceed?", default=True)
        assert result is True

    def test_prompt_confirm_no(self):
        prompter = InteractivePrompter(use_colors=False)
        prompter.input_fn = lambda prompt: "n"

        result = prompter.prompt_confirm("Proceed?", default=True)
        assert result is False

    def test_prompt_confirm_empty_uses_default_true(self):
        prompter = InteractivePrompter(use_colors=False)
        prompter.input_fn = lambda prompt: ""

        result = prompter.prompt_confirm("Proceed?", default=True)
        assert result is True

    def test_prompt_confirm_empty_uses_default_false(self):
        prompter = InteractivePrompter(use_colors=False)
        prompter.input_fn = lambda prompt: ""

        result = prompter.prompt_confirm("Proceed?", default=False)
        assert result is False

    def test_prompt_select_valid_choice(self):
        prompter = InteractivePrompter(use_colors=False)
        prompter.input_fn = lambda prompt: "2"

        options = [("Option A", "a"), ("Option B", "b"), ("Option C", "c")]
        result = prompter.prompt_select("Choose:", options)
        assert result == "b"

    def test_prompt_select_default_value(self):
        prompter = InteractivePrompter(use_colors=False)
        prompter.input_fn = lambda prompt: ""

        options = [("Option A", "a"), ("Option B", "b")]
        result = prompter.prompt_select("Choose:", options, default=1)
        assert result == "b"

    def test_prompt_select_invalid_retries(self):
        prompter = InteractivePrompter(use_colors=False)
        calls = ["5", "1"]
        prompter.input_fn = lambda prompt: calls.pop(0)

        options = [("Option A", "a"), ("Option B", "b")]
        result = prompter.prompt_select("Choose:", options)
        assert result == "a"

    @pytest.mark.asyncio
    async def test_prompt_for_template(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            manager = TemplateManager(templates_dir=tmpdir)
            template = manager.get_template("python-service")

            prompter = InteractivePrompter(use_colors=False)
            
            input_values = iter([
                "test-project",  # project_name
                "",              # author (default)
                "2",             # python_version (3.10)
                "n",             # use_database
                "3",             # database_type (sqlite)
                "n",             # use_redis
                "",              # use_docker (default)
                "",              # use_logging (default)
            ])
            prompter.input_fn = lambda prompt: next(input_values)

            variables = await prompter.prompt_for_template(template)
            assert variables["project_name"] == "test-project"


# ============================================================================
# ProjectScaffolder Unit Tests
# ============================================================================


class TestProjectScaffolder:
    """Unit tests for ProjectScaffolder."""

    # --- Initialization Tests ---

    def test_initialization_defaults(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            output_dir = Path(tmpdir) / "output"
            scaffolder = ProjectScaffolder(
                templates_dir=tmpdir,
                output_dir=str(output_dir),
            )
            assert scaffolder.output_dir.exists()

    def test_initialization_creates_directories(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            new_dir = Path(tmpdir) / "nonexistent" / "output"
            scaffolder = ProjectScaffolder(
                templates_dir=tmpdir,
                output_dir=str(new_dir),
            )
            assert new_dir.exists()

    # --- Case Conversion Tests ---

    def test_camel_case_conversion(self):
        assert ProjectScaffolder._camel_case("hello_world") == "helloWorld"
        assert ProjectScaffolder._camel_case("HelloWorld") == "helloWorld"
        assert ProjectScaffolder._camel_case("hello-world") == "helloWorld"
        assert ProjectScaffolder._camel_case("hello") == "hello"

    def test_pascal_case_conversion(self):
        assert ProjectScaffolder._pascal_case("hello_world") == "HelloWorld"
        assert ProjectScaffolder._pascal_case("hello-world") == "HelloWorld"
        assert ProjectScaffolder._pascal_case("hello world") == "HelloWorld"

    def test_snake_case_conversion(self):
        assert ProjectScaffolder._snake_case("HelloWorld") == "hello_world"
        assert ProjectScaffolder._snake_case("hello-world") == "hello_world"
        assert ProjectScaffolder._snake_case("hello world") == "hello_world"

    def test_kebab_case_conversion(self):
        assert ProjectScaffolder._kebab_case("HelloWorld") == "hello-world"
        assert ProjectScaffolder._kebab_case("hello_world") == "hello-world"
        assert ProjectScaffolder._kebab_case("hello world") == "hello-world"

    def test_case_conversion_empty_string(self):
        assert ProjectScaffolder._camel_case("") == ""
        assert ProjectScaffolder._pascal_case("") == ""
        assert ProjectScaffolder._snake_case("") == ""
        assert ProjectScaffolder._kebab_case("") == ""

    def test_case_conversion_special_characters(self):
        assert ProjectScaffolder._snake_case("hello@@world##") == "hello_world"
        assert ProjectScaffolder._kebab_case("hello@@world##") == "hello-world"

    # --- Path Normalization Tests ---

    def test_normalize_path_windows(self):
        assert ProjectScaffolder._normalize_path("src\\main.py") == "src/main.py"
        assert ProjectScaffolder._normalize_path("a\\b\\c") == "a/b/c"

    def test_normalize_path_unix(self):
        assert ProjectScaffolder._normalize_path("src/main.py") == "src/main.py"
        assert ProjectScaffolder._normalize_path("a/b/c") == "a/b/c"

    # --- Template Info Tests ---

    def test_get_template_info(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            scaffolder = ProjectScaffolder(
                templates_dir=tmpdir,
                output_dir=str(Path(tmpdir) / "output"),
            )

            info = scaffolder.get_template_info("python-service")
            assert info is not None
            assert info["template_id"] == "python-service"
            assert info["name"] == "Python Microservice"
            assert "variables" in info

    def test_get_template_info_nonexistent(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            scaffolder = ProjectScaffolder(
                templates_dir=tmpdir,
                output_dir=str(Path(tmpdir) / "output"),
            )

            info = scaffolder.get_template_info("nonexistent")
            assert info is None

    def test_list_templates(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            scaffolder = ProjectScaffolder(
                templates_dir=tmpdir,
                output_dir=str(Path(tmpdir) / "output"),
            )

            templates = scaffolder.list_templates()
            assert len(templates) >= 4

    # --- File Backup Tests ---

    def test_backup_existing_directory(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            project_dir = Path(tmpdir) / "my-project"
            project_dir.mkdir()
            (project_dir / "old_file.txt").write_text("old content")

            ProjectScaffolder._backup_existing_dir(project_dir)

            assert not project_dir.exists()
            backups = list(Path(tmpdir).glob("my-project.*.bak"))
            assert len(backups) == 1

    def test_backup_nonexistent_directory(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            project_dir = Path(tmpdir) / "nonexistent"
            ProjectScaffolder._backup_existing_dir(project_dir)

    # --- File Writing Tests ---

    def test_write_file_creates_parent_directories(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            file_path = Path(tmpdir) / "a" / "b" / "c" / "test.txt"
            ProjectScaffolder._write_file(file_path, "content")

            assert file_path.exists()
            assert file_path.read_text() == "content"

    def test_write_file_overwrites(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            file_path = Path(tmpdir) / "test.txt"
            file_path.write_text("old")
            ProjectScaffolder._write_file(file_path, "new")

            assert file_path.read_text() == "new"


# ============================================================================
# ProjectScaffolder Integration Tests
# ============================================================================


class TestProjectScaffolderIntegration:
    """Integration tests for ProjectScaffolder with real file generation."""

    # --- Normal Path Tests ---

    def test_generate_from_builtin_template(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            templates_dir = Path(tmpdir) / "templates"
            output_dir = Path(tmpdir) / "output"

            template_dir = templates_dir / "python-service"
            template_dir.mkdir(parents=True)
            (template_dir / "README.md.j2").write_text("# {{ project_name }}")
            (template_dir / "setup.py").write_text("print('{{ project_name }}')")

            scaffolder = ProjectScaffolder(
                templates_dir=str(templates_dir),
                output_dir=str(output_dir),
            )

            variables = create_template_variables_dict()
            project_path, generated_files = scaffolder.generate_from_template(
                "python-service",
                variables,
                output_subdir="my-project",
            )

            assert project_path.exists()
            assert len(generated_files) >= 2

            readme = project_path / "README.md"
            assert readme.exists()
            assert "# test-project" in readme.read_text()

            manifest_path = project_path / ".scaffold-manifest.json"
            assert manifest_path.exists()
            manifest = json.loads(manifest_path.read_text())
            assert manifest["template_id"] == "python-service"

    def test_generate_with_custom_template(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            output_dir = Path(tmpdir) / "output"

            scaffolder = ProjectScaffolder(
                templates_dir=str(Path(tmpdir) / "templates"),
                output_dir=str(output_dir),
            )

            template = TemplateBuilder() \
                .with_id("custom-tpl") \
                .with_name("Custom") \
                .with_description("Custom template") \
                .with_directory("custom") \
                .add_variable(
                    TemplateVariableBuilder()
                    .with_name("name")
                    .with_description("Name")
                    .build()
                ) \
                .build()

            template_files = {
                "greeting.txt.j2": "Hello, {{ name }}!",
            }
            scaffolder.template_manager.register_template(template, template_files)

            project_path, files = scaffolder.generate_from_template(
                "custom-tpl",
                {"name": "World"},
                output_subdir="custom-project",
            )

            greeting = project_path / "greeting.txt"
            assert greeting.read_text() == "Hello, World!"

    def test_generate_with_nested_directories(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            templates_dir = Path(tmpdir) / "templates"
            output_dir = Path(tmpdir) / "output"

            template_dir = templates_dir / "nested-tpl"
            (template_dir / "src").mkdir(parents=True)
            (template_dir / "tests").mkdir(parents=True)
            (template_dir / "src" / "main.py.j2").write_text("print('{{ name }}')")
            (template_dir / "tests" / "test_main.py.j2").write_text("def test_{{ name }}(): pass")

            scaffolder = ProjectScaffolder(
                templates_dir=str(templates_dir),
                output_dir=str(output_dir),
            )

            template = TemplateBuilder() \
                .with_id("nested-tpl") \
                .with_name("Nested") \
                .with_description("Nested template") \
                .with_directory("nested-tpl") \
                .add_variable(
                    TemplateVariableBuilder()
                    .with_name("name")
                    .build()
                ) \
                .build()

            scaffolder.template_manager._templates["nested-tpl"] = template

            project_path, files = scaffolder.generate_from_template(
                "nested-tpl",
                {"name": "myapp"},
                output_subdir="nested-project",
            )

            assert (project_path / "src" / "main.py").exists()
            assert (project_path / "tests" / "test_main.py").exists()

    def test_generate_with_jinja_filters(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            templates_dir = Path(tmpdir) / "templates"
            output_dir = Path(tmpdir) / "output"

            template_dir = templates_dir / "filter-tpl"
            template_dir.mkdir(parents=True)
            (template_dir / "module.py.j2").write_text(
                "class {{ name | pascal_case }}:\n"
                "    def __init__(self):\n"
                "        self.{{ name | snake_case }} = None"
            )

            scaffolder = ProjectScaffolder(
                templates_dir=str(templates_dir),
                output_dir=str(output_dir),
            )

            template = TemplateBuilder() \
                .with_id("filter-tpl") \
                .with_name("Filter") \
                .with_description("Template with filters") \
                .with_directory("filter-tpl") \
                .add_variable(
                    TemplateVariableBuilder()
                    .with_name("name")
                    .build()
                ) \
                .build()

            scaffolder.template_manager._templates["filter-tpl"] = template

            project_path, _ = scaffolder.generate_from_template(
                "filter-tpl",
                {"name": "my_service"},
                output_subdir="filter-project",
            )

            module_file = project_path / "module.py"
            content = module_file.read_text()
            assert "class MyService:" in content
            assert "self.my_service = None" in content

    def test_generate_with_extra_files(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            templates_dir = Path(tmpdir) / "templates"
            output_dir = Path(tmpdir) / "output"

            template_dir = templates_dir / "extra-tpl"
            template_dir.mkdir(parents=True)
            (template_dir / "base.txt.j2").write_text("Base: {{ name }}")

            scaffolder = ProjectScaffolder(
                templates_dir=str(templates_dir),
                output_dir=str(output_dir),
            )

            template = TemplateBuilder() \
                .with_id("extra-tpl") \
                .with_name("Extra") \
                .with_directory("extra-tpl") \
                .add_variable(TemplateVariableBuilder().with_name("name").build()) \
                .build()
            scaffolder.template_manager._templates["extra-tpl"] = template

            extra_files = [
                GeneratedFileBuilder()
                .with_path("extra.txt")
                .with_content("Extra content")
                .build(),
            ]

            project_path, generated = scaffolder.generate_from_template(
                "extra-tpl",
                {"name": "test"},
                output_subdir="extra-project",
                extra_files=extra_files,
            )

            assert (project_path / "extra.txt").exists()
            assert len(generated) == 2

    def test_generate_backs_up_existing_project(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            templates_dir = Path(tmpdir) / "templates"
            output_dir = Path(tmpdir) / "output"

            template_dir = templates_dir / "backup-tpl"
            template_dir.mkdir(parents=True)
            (template_dir / "file.txt.j2").write_text("New: {{ name }}")

            scaffolder = ProjectScaffolder(
                templates_dir=str(templates_dir),
                output_dir=str(output_dir),
            )

            template = TemplateBuilder() \
                .with_id("backup-tpl") \
                .with_name("Backup") \
                .with_directory("backup-tpl") \
                .add_variable(TemplateVariableBuilder().with_name("name").build()) \
                .build()
            scaffolder.template_manager._templates["backup-tpl"] = template

            project_path = output_dir / "my-project"
            project_path.mkdir(parents=True)
            (project_path / "old.txt").write_text("Old content")

            scaffolder.generate_from_template(
                "backup-tpl",
                {"name": "test"},
                output_subdir="my-project",
            )

            backups = list(output_dir.glob("my-project.*.bak"))
            assert len(backups) >= 1
            assert (project_path / "file.txt").exists()

    # --- Boundary Input Tests ---

    def test_generate_empty_template(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            templates_dir = Path(tmpdir) / "templates"
            output_dir = Path(tmpdir) / "output"

            template_dir = templates_dir / "empty-tpl"
            template_dir.mkdir(parents=True)

            scaffolder = ProjectScaffolder(
                templates_dir=str(templates_dir),
                output_dir=str(output_dir),
            )

            template = TemplateBuilder() \
                .with_id("empty-tpl") \
                .with_name("Empty") \
                .with_directory("empty-tpl") \
                .build()
            scaffolder.template_manager._templates["empty-tpl"] = template

            project_path, generated = scaffolder.generate_from_template(
                "empty-tpl",
                {},
                output_subdir="empty-project",
            )

            assert project_path.exists()
            assert len(generated) == 0

    def test_generate_with_special_characters_in_variables(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            templates_dir = Path(tmpdir) / "templates"
            output_dir = Path(tmpdir) / "output"

            template_dir = templates_dir / "special-tpl"
            template_dir.mkdir(parents=True)
            (template_dir / "output.txt.j2").write_text("Value: {{ value }}")

            scaffolder = ProjectScaffolder(
                templates_dir=str(templates_dir),
                output_dir=str(output_dir),
            )

            template = TemplateBuilder() \
                .with_id("special-tpl") \
                .with_name("Special") \
                .with_directory("special-tpl") \
                .add_variable(TemplateVariableBuilder().with_name("value").build()) \
                .build()
            scaffolder.template_manager._templates["special-tpl"] = template

            project_path, _ = scaffolder.generate_from_template(
                "special-tpl",
                {"value": "special & < > \" quote"},
                output_subdir="special-project",
            )

            content = (project_path / "output.txt").read_text()
            assert "special & < > \" quote" in content

    def test_generate_with_large_number_of_files(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            templates_dir = Path(tmpdir) / "templates"
            output_dir = Path(tmpdir) / "output"

            template_dir = templates_dir / "large-tpl"
            template_dir.mkdir(parents=True)

            for i in range(50):
                (template_dir / f"file_{i:03d}.txt.j2").write_text(f"File {i}: {{ name }}")

            scaffolder = ProjectScaffolder(
                templates_dir=str(templates_dir),
                output_dir=str(output_dir),
            )

            template = TemplateBuilder() \
                .with_id("large-tpl") \
                .with_name("Large") \
                .with_directory("large-tpl") \
                .add_variable(TemplateVariableBuilder().with_name("name").build()) \
                .build()
            scaffolder.template_manager._templates["large-tpl"] = template

            project_path, generated = scaffolder.generate_from_template(
                "large-tpl",
                {"name": "test"},
                output_subdir="large-project",
            )

            assert len(generated) == 50
            for i in range(50):
                assert (project_path / f"file_{i:03d}.txt").exists()

    # --- Exception Injection Tests ---

    def test_generate_nonexistent_template_raises(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            scaffolder = ProjectScaffolder(
                templates_dir=str(Path(tmpdir) / "templates"),
                output_dir=str(Path(tmpdir) / "output"),
            )

            with pytest.raises(ValidationError) as exc:
                scaffolder.generate_from_template("nonexistent", {})
            assert "not found" in str(exc.value.message)

    def test_generate_invalid_variables_raises(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            templates_dir = Path(tmpdir) / "templates"
            output_dir = Path(tmpdir) / "output"

            template_dir = templates_dir / "validate-tpl"
            template_dir.mkdir(parents=True)

            scaffolder = ProjectScaffolder(
                templates_dir=str(templates_dir),
                output_dir=str(output_dir),
            )

            template = TemplateBuilder() \
                .with_id("validate-tpl") \
                .with_name("Validate") \
                .with_directory("validate-tpl") \
                .add_variable(
                    TemplateVariableBuilder()
                    .with_name("name")
                    .required()
                    .build()
                ) \
                .build()
            scaffolder.template_manager._templates["validate-tpl"] = template

            with pytest.raises(ValidationError) as exc:
                scaffolder.generate_from_template("validate-tpl", {})
            assert "Invalid variables" in str(exc.value.message)

    def test_generate_with_template_render_error(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            templates_dir = Path(tmpdir) / "templates"
            output_dir = Path(tmpdir) / "output"

            template_dir = templates_dir / "error-tpl"
            template_dir.mkdir(parents=True)
            (template_dir / "broken.txt.j2").write_text("{% if %}Broken{% endif %}")

            scaffolder = ProjectScaffolder(
                templates_dir=str(templates_dir),
                output_dir=str(output_dir),
            )

            template = TemplateBuilder() \
                .with_id("error-tpl") \
                .with_name("Error") \
                .with_directory("error-tpl") \
                .build()
            scaffolder.template_manager._templates["error-tpl"] = template

            project_path, generated = scaffolder.generate_from_template(
                "error-tpl",
                {},
                output_subdir="error-project",
            )

            assert (project_path / "broken.txt").exists()

    @pytest.mark.asyncio
    async def test_interactive_generate_user_cancels(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            scaffolder = ProjectScaffolder(
                templates_dir=str(Path(tmpdir) / "templates"),
                output_dir=str(Path(tmpdir) / "output"),
            )

            with patch(
                "src.scaffolder.scaffolder.InteractivePrompter"
            ) as MockPrompter:
                mock_instance = MockPrompter.return_value
                mock_instance.prompt_select = AsyncMock(return_value="python-service")
                mock_instance.prompt_for_template = AsyncMock(
                    return_value=create_template_variables_dict()
                )
                mock_instance.prompt_confirm = MagicMock(return_value=False)

                with pytest.raises(ConfigurationError) as exc:
                    await scaffolder.interactive_generate()
                assert "cancelled" in str(exc.value.message)


# ============================================================================
# GeneratedFile Tests
# ============================================================================


class TestGeneratedFile:
    """Tests for GeneratedFile dataclass."""

    def test_default_creation(self):
        gf = GeneratedFile(
            path="src/main.py",
            content="print('hello')",
            template_source="src/main.py.j2",
        )
        assert gf.path == "src/main.py"
        assert gf.content == "print('hello')"
        assert gf.is_binary is False

    def test_binary_file(self):
        gf = GeneratedFile(
            path="image.png",
            content="binary_data",
            is_binary=True,
        )
        assert gf.is_binary is True

    def test_generated_file_builder(self):
        gf = GeneratedFileBuilder() \
            .with_path("test.txt") \
            .with_content("Hello") \
            .from_template("test.txt.j2") \
            .build()

        assert gf.path == "test.txt"
        assert gf.content == "Hello"
        assert gf.template_source == "test.txt.j2"


# ============================================================================
# Concurrent Operation Tests
# ============================================================================


class TestScaffolderConcurrent:
    """Tests for concurrent scaffold generation operations."""

    @pytest.mark.asyncio
    async def test_concurrent_generations(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            templates_dir = Path(tmpdir) / "templates"
            output_dir = Path(tmpdir) / "output"

            template_dir = templates_dir / "concurrent-tpl"
            template_dir.mkdir(parents=True)
            (template_dir / "output.txt.j2").write_text("Project: {{ name }}")

            scaffolder = ProjectScaffolder(
                templates_dir=str(templates_dir),
                output_dir=str(output_dir),
            )

            template = TemplateBuilder() \
                .with_id("concurrent-tpl") \
                .with_name("Concurrent") \
                .with_directory("concurrent-tpl") \
                .add_variable(TemplateVariableBuilder().with_name("name").build()) \
                .build()
            scaffolder.template_manager._templates["concurrent-tpl"] = template

            async def generate_project(project_name: str):
                return scaffolder.generate_from_template(
                    "concurrent-tpl",
                    {"name": project_name},
                    output_subdir=project_name,
                )

            tasks = [generate_project(f"project_{i}") for i in range(5)]
            results = await asyncio.gather(*tasks)

            for project_path, files in results:
                assert project_path.exists()
                assert (project_path / "output.txt").exists()

            for i in range(5):
                assert (output_dir / f"project_{i}").exists()
