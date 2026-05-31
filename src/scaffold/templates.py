from __future__ import annotations

import logging
import os
from typing import Any, Dict, List, Optional

from jinja2 import Environment, FileSystemLoader, StrictUndefined

from src.scaffold.models import (
    DatabaseType,
    ProjectType,
    TemplateDefinition,
    TemplateFile,
    TemplateVariable,
)

logger = logging.getLogger(__name__)


class TemplateEngine:
    def __init__(self, template_dir: Optional[str] = None) -> None:
        self.template_dir = template_dir or os.path.join(os.path.dirname(__file__), "templates")
        self.env = Environment(
            loader=FileSystemLoader(self.template_dir),
            undefined=StrictUndefined,
            keep_trailing_newline=True,
        )
        self._register_filters()

    def _register_filters(self) -> None:
        self.env.filters["to_python_class"] = self._to_python_class
        self.env.filters["to_snake_case"] = self._to_snake_case
        self.env.filters["to_kebab_case"] = self._to_kebab_case
        self.env.filters["to_constant_case"] = self._to_constant_case
        self.env.filters["to_plural"] = self._to_plural

    @staticmethod
    def _to_python_class(s: str) -> str:
        parts = s.replace("-", "_").replace(" ", "_").split("_")
        return "".join(p.capitalize() for p in parts if p)

    @staticmethod
    def _to_snake_case(s: str) -> str:
        import re
        s1 = re.sub("(.)([A-Z][a-z]+)", r"\1_\2", s)
        return re.sub("([a-z0-9])([A-Z])", r"\1_\2", s1).lower()

    @staticmethod
    def _to_kebab_case(s: str) -> str:
        return TemplateEngine._to_snake_case(s).replace("_", "-")

    @staticmethod
    def _to_constant_case(s: str) -> str:
        return TemplateEngine._to_snake_case(s).upper()

    @staticmethod
    def _to_plural(s: str) -> str:
        if s.endswith("y"):
            return s[:-1] + "ies"
        if s.endswith(("s", "x", "z", "ch", "sh")):
            return s + "es"
        return s + "s"

    def render_string(self, template_str: str, context: Dict[str, Any]) -> str:
        return self.env.from_string(template_str).render(**context)

    def render_file(self, template_path: str, context: Dict[str, Any]) -> str:
        return self.env.get_template(template_path).render(**context)


class TemplateRepository:
    def __init__(self) -> None:
        self._templates: Dict[str, TemplateDefinition] = {}
        self._register_default_templates()

    def _register_default_templates(self) -> None:
        self._templates["fastapi-basic"] = self._create_fastapi_template()
        self._templates["python-library"] = self._create_library_template()
        self._templates["cli-app"] = self._create_cli_template()

    def _create_fastapi_template(self) -> TemplateDefinition:
        return TemplateDefinition(
            name="FastAPI Basic",
            description="A basic FastAPI project with REST API structure",
            project_type=ProjectType.FASTAPI,
            variables=[
                TemplateVariable(name="project_name", description="Name of the project", required=True),
                TemplateVariable(name="author", description="Author name", required=True),
                TemplateVariable(name="email", description="Author email", required=True),
                TemplateVariable(name="description", description="Project description", default=""),
                TemplateVariable(name="version", description="Initial version", default="0.1.0"),
                TemplateVariable(
                    name="database",
                    description="Database type",
                    default=DatabaseType.NONE.value,
                    choices=[d.value for d in DatabaseType],
                ),
                TemplateVariable(
                    name="include_auth",
                    description="Include authentication",
                    type="boolean",
                    default=True,
                ),
                TemplateVariable(
                    name="include_docker",
                    description="Include Docker support",
                    type="boolean",
                    default=True,
                ),
            ],
            files=[
                TemplateFile(source_path="fastapi/pyproject.toml.j2", target_path="pyproject.toml"),
                TemplateFile(source_path="fastapi/requirements.txt.j2", target_path="requirements.txt"),
                TemplateFile(source_path="fastapi/main.py.j2", target_path="main.py"),
                TemplateFile(source_path="fastapi/README.md.j2", target_path="README.md"),
                TemplateFile(source_path="fastapi/.env.example.j2", target_path=".env.example"),
                TemplateFile(source_path="fastapi/.gitignore.j2", target_path=".gitignore"),
                TemplateFile(source_path="fastapi/app/__init__.py.j2", target_path="app/__init__.py"),
                TemplateFile(source_path="fastapi/app/config.py.j2", target_path="app/config.py"),
                TemplateFile(source_path="fastapi/app/models.py.j2", target_path="app/models.py"),
                TemplateFile(source_path="fastapi/app/schemas.py.j2", target_path="app/schemas.py"),
                TemplateFile(source_path="fastapi/app/routers/__init__.py.j2", target_path="app/routers/__init__.py"),
                TemplateFile(source_path="fastapi/app/routers/items.py.j2", target_path="app/routers/items.py"),
                TemplateFile(source_path="fastapi/tests/__init__.py.j2", target_path="tests/__init__.py"),
                TemplateFile(source_path="fastapi/tests/conftest.py.j2", target_path="tests/conftest.py"),
            ],
            dependencies=[
                "fastapi>=0.104.0",
                "uvicorn[standard]>=0.24.0",
                "pydantic>=2.5.0",
                "python-multipart>=0.0.6",
            ],
            dev_dependencies=[
                "pytest>=7.4.0",
                "pytest-asyncio>=0.21.0",
                "httpx>=0.25.0",
            ],
        )

    def _create_library_template(self) -> TemplateDefinition:
        return TemplateDefinition(
            name="Python Library",
            description="A Python library package structure",
            project_type=ProjectType.LIBRARY,
            variables=[
                TemplateVariable(name="library_name", description="Library name", required=True),
                TemplateVariable(name="author", description="Author name", required=True),
                TemplateVariable(name="description", description="Library description", default=""),
                TemplateVariable(name="version", description="Initial version", default="0.1.0"),
            ],
            files=[
                TemplateFile(source_path="library/pyproject.toml.j2", target_path="pyproject.toml"),
                TemplateFile(source_path="library/README.md.j2", target_path="README.md"),
                TemplateFile(source_path="library/.gitignore.j2", target_path=".gitignore"),
                TemplateFile(source_path="library/src/__init__.py.j2", target_path="src/{{ library_name }}/__init__.py"),
                TemplateFile(source_path="library/src/core.py.j2", target_path="src/{{ library_name }}/core.py"),
                TemplateFile(source_path="library/tests/__init__.py.j2", target_path="tests/__init__.py"),
                TemplateFile(source_path="library/tests/test_core.py.j2", target_path="tests/test_core.py"),
            ],
            dependencies=[],
            dev_dependencies=[
                "pytest>=7.4.0",
                "pytest-cov>=4.1.0",
            ],
        )

    def _create_cli_template(self) -> TemplateDefinition:
        return TemplateDefinition(
            name="CLI Application",
            description="A Python CLI application using Click",
            project_type=ProjectType.CLI,
            variables=[
                TemplateVariable(name="cli_name", description="CLI command name", required=True),
                TemplateVariable(name="description", description="CLI description", default=""),
                TemplateVariable(name="author", description="Author name", required=True),
            ],
            files=[
                TemplateFile(source_path="cli/pyproject.toml.j2", target_path="pyproject.toml"),
                TemplateFile(source_path="cli/README.md.j2", target_path="README.md"),
                TemplateFile(source_path="cli/.gitignore.j2", target_path=".gitignore"),
                TemplateFile(source_path="cli/cli.py.j2", target_path="cli.py", executable=True),
                TemplateFile(source_path="cli/commands/__init__.py.j2", target_path="commands/__init__.py"),
                TemplateFile(source_path="cli/commands/greet.py.j2", target_path="commands/greet.py"),
            ],
            dependencies=[
                "click>=8.1.7",
                "rich>=13.7.0",
            ],
            dev_dependencies=[
                "pytest>=7.4.0",
            ],
        )

    def get(self, template_id: str) -> Optional[TemplateDefinition]:
        return self._templates.get(template_id)

    def list(self) -> List[TemplateDefinition]:
        return list(self._templates.values())

    def add(self, template: TemplateDefinition) -> None:
        self._templates[template.template_id] = template
