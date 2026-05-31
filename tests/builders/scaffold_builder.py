from __future__ import annotations

import copy
import tempfile
from pathlib import Path
from typing import Any, Dict, List, Optional


class ScaffoldBuilder:
    _counter = 0

    def __init__(self):
        ScaffoldBuilder._counter += 1
        self._template_name = "go-service"
        self._module_name = f"github.com/test/testapp{ScaffoldBuilder._counter}"
        self._service_name = f"TestApp{ScaffoldBuilder._counter}"
        self._author = "Test Developer"
        self._with_docker = True
        self._with_ci = False
        self._output_dir: Optional[str] = None
        self._custom_params: Dict[str, Any] = {}

    def with_template(self, template_name: str) -> "ScaffoldBuilder":
        self._template_name = template_name
        return self

    def with_module_name(self, module_name: str) -> "ScaffoldBuilder":
        self._module_name = module_name
        return self

    def with_service_name(self, service_name: str) -> "ScaffoldBuilder":
        self._service_name = service_name
        return self

    def with_author(self, author: str) -> "ScaffoldBuilder":
        self._author = author
        return self

    def with_docker(self, enabled: bool) -> "ScaffoldBuilder":
        self._with_docker = enabled
        return self

    def with_ci(self, enabled: bool) -> "ScaffoldBuilder":
        self._with_ci = enabled
        return self

    def with_output_dir(self, output_dir: str) -> "ScaffoldBuilder":
        self._output_dir = output_dir
        return self

    def with_custom_param(self, key: str, value: Any) -> "ScaffoldBuilder":
        self._custom_params[key] = value
        return self

    def with_temp_output_dir(self) -> "ScaffoldBuilder":
        temp_dir = tempfile.mkdtemp(prefix=f"scaffold_{ScaffoldBuilder._counter}_")
        self._output_dir = temp_dir
        return self

    def build_params(self) -> Dict[str, Any]:
        params = {
            "module_name": self._module_name,
            "service_name": self._service_name,
            "author": self._author,
            "with_docker": self._with_docker,
            "with_ci": self._with_ci,
        }
        params.update(self._custom_params)
        return params

    def build_request(self) -> Dict[str, Any]:
        request = {
            "template_name": self._template_name,
            "params": self.build_params(),
        }
        if self._output_dir:
            request["output_dir"] = self._output_dir
        return request

    def build_template_definition(self) -> Dict[str, Any]:
        return {
            "name": self._template_name,
            "description": f"Template for {self._template_name}",
            "language": "go" if self._template_name == "go-service" else "python",
            "type": "service",
            "params": [
                {"name": "module_name", "description": "Go module name", "type": "string", "required": True},
                {"name": "service_name", "description": "Service name", "type": "string", "required": True},
                {"name": "author", "description": "Author name", "type": "string", "required": False, "default": "Developer"},
                {"name": "with_docker", "description": "Include Dockerfile", "type": "boolean", "required": False, "default": True},
                {"name": "with_ci", "description": "Include CI config", "type": "boolean", "required": False, "default": False},
            ],
            "files": [
                {"path": "go.mod", "content": "module {{.module_name}}\n\ngo 1.21\n"},
                {"path": "main.go", "content": "package main\n\nfunc main() {\n    println(\"{{.service_name}}\")\n}\n"},
                {"path": "README.md", "content": "# {{.service_name}}\n\nAuthor: {{.author}}\n"},
            ],
        }

    @staticmethod
    def create_default_request() -> Dict[str, Any]:
        return ScaffoldBuilder().build_request()

    @staticmethod
    def create_go_service_request() -> Dict[str, Any]:
        return ScaffoldBuilder().with_template("go-service").build_request()

    @staticmethod
    def create_python_api_request() -> Dict[str, Any]:
        builder = ScaffoldBuilder().with_template("python-api")
        builder._custom_params = {"project_name": builder._service_name, "version": "0.1.0"}
        return builder.build_request()

    @staticmethod
    def create_react_app_request() -> Dict[str, Any]:
        builder = ScaffoldBuilder().with_template("react-app")
        builder._custom_params = {"app_name": builder._service_name}
        return builder.build_request()

    @staticmethod
    def create_concurrent_requests(count: int) -> List[Dict[str, Any]]:
        requests = []
        for i in range(count):
            builder = ScaffoldBuilder()
            builder._service_name = f"ConcurrentApp{i}"
            builder._module_name = f"github.com/test/concurrent{i}"
            builder.with_temp_output_dir()
            requests.append(builder.build_request())
        return requests

    @staticmethod
    def create_with_conflicting_output_dir() -> List[Dict[str, Any]]:
        shared_dir = tempfile.mkdtemp(prefix="scaffold_conflict_")
        requests = []

        for i in range(3):
            builder = ScaffoldBuilder()
            builder._service_name = f"ConflictApp{i}"
            builder._module_name = f"github.com/test/conflict{i}"
            builder.with_output_dir(shared_dir)
            requests.append(builder.build_request())

        return requests

    @staticmethod
    def cleanup_temp_dir(path: str):
        import shutil
        try:
            shutil.rmtree(path)
        except Exception:
            pass
