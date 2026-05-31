"""
模板引擎基础设施实现
"""

from __future__ import annotations

import os
import re
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set

from src.domain.contracts.template import FileSystemProtocol, TemplateEngineProtocol
from src.domain.errors.template import TemplateError


class InMemoryFileSystem(FileSystemProtocol):
    def __init__(self) -> None:
        self._files: Dict[str, str] = {}
        self._dirs: Set[str] = {""}

    def _normalize_path(self, path: str) -> str:
        return os.path.normpath(path).replace(os.sep, "/")

    def write_file(self, path: str, content: str) -> None:
        norm_path = self._normalize_path(path)
        dir_path = os.path.dirname(norm_path)
        if dir_path:
            self.create_dir(dir_path)
        self._files[norm_path] = content

    def read_file(self, path: str) -> str:
        norm_path = self._normalize_path(path)
        if norm_path not in self._files:
            raise FileNotFoundError(f"File not found: {path}")
        return self._files[norm_path]

    def exists(self, path: str) -> bool:
        norm_path = self._normalize_path(path)
        return norm_path in self._files or norm_path in self._dirs

    def create_dir(self, path: str) -> None:
        norm_path = self._normalize_path(path)
        parts = norm_path.split("/")
        current = ""
        for part in parts:
            if part:
                current = f"{current}/{part}" if current else part
                self._dirs.add(current)

    def list_dir(self, path: str) -> List[str]:
        norm_path = self._normalize_path(path)
        prefix = norm_path + "/" if norm_path else ""
        results = []
        for f in self._files:
            if f.startswith(prefix):
                rest = f[len(prefix):]
                if "/" not in rest:
                    results.append(rest)
        for d in self._dirs:
            if d.startswith(prefix) and d != norm_path:
                rest = d[len(prefix):]
                if "/" not in rest:
                    results.append(rest + "/")
        return results

    def get_all_files(self) -> Dict[str, str]:
        return dict(self._files)


class FileSystemAdapter(FileSystemProtocol):
    def __init__(self, base_path: str = "") -> None:
        self.base_path = os.path.abspath(base_path) if base_path else ""

    def _resolve_path(self, path: str) -> str:
        if self.base_path:
            return os.path.join(self.base_path, path)
        return path

    def write_file(self, path: str, content: str) -> None:
        full_path = self._resolve_path(path)
        os.makedirs(os.path.dirname(full_path) or ".", exist_ok=True)
        with open(full_path, "w", encoding="utf-8") as f:
            f.write(content)

    def read_file(self, path: str) -> str:
        with open(self._resolve_path(path), "r", encoding="utf-8") as f:
            return f.read()

    def exists(self, path: str) -> bool:
        return os.path.exists(self._resolve_path(path))

    def create_dir(self, path: str) -> None:
        os.makedirs(self._resolve_path(path), exist_ok=True)

    def list_dir(self, path: str) -> List[str]:
        full_path = self._resolve_path(path)
        if not os.path.isdir(full_path):
            return []
        result = []
        for entry in os.listdir(full_path):
            entry_path = os.path.join(full_path, entry)
            result.append(entry + "/" if os.path.isdir(entry_path) else entry)
        return result


class Jinja2TemplateEngine(TemplateEngineProtocol):
    def __init__(self, template_dir: str = "") -> None:
        self.template_dir = template_dir
        self._env = None
        self._init_env()

    def _init_env(self) -> None:
        try:
            from jinja2 import Environment, FileSystemLoader, BaseLoader
            if self.template_dir and os.path.exists(self.template_dir):
                self._env = Environment(loader=FileSystemLoader(self.template_dir), trim_blocks=True, lstrip_blocks=True, keep_trailing_newline=True)
            else:
                self._env = Environment(loader=BaseLoader(), trim_blocks=True, lstrip_blocks=True, keep_trailing_newline=True)
        except ImportError:
            self._env = None

    def render_string(self, template: str, context: Dict[str, Any]) -> str:
        if self._env is None:
            return self._simple_render(template, context)
        try:
            return self._env.from_string(template).render(**context)
        except Exception as e:
            raise TemplateError(f"Template render failed: {e}") from e

    def _simple_render(self, template: str, context: Dict[str, Any]) -> str:
        def replace_var(match):
            return str(context.get(match.group(1).strip(), match.group(0)))
        return re.sub(r"\{\{\s*(\w+)\s*\}\}", replace_var, template)

    def render_file(self, template_path: str, output_path: str, context: Dict[str, Any]) -> None:
        try:
            full_template_path = os.path.join(self.template_dir, template_path)
            if os.path.exists(full_template_path):
                with open(full_template_path, "r", encoding="utf-8") as f:
                    template = f.read()
            else:
                template = template_path
            rendered = self.render_string(template, context)
            output_dir = os.path.dirname(output_path)
            if output_dir:
                os.makedirs(output_dir, exist_ok=True)
            with open(output_path, "w", encoding="utf-8") as f:
                f.write(rendered)
        except TemplateError:
            raise
        except Exception as e:
            raise TemplateError(f"File render failed: {e}", template=template_path) from e

    def list_templates(self, template_dir: str) -> List[str]:
        target_dir = template_dir or self.template_dir
        if not target_dir or not os.path.exists(target_dir):
            return []
        templates = []
        for root, _, files in os.walk(target_dir):
            for file in files:
                if file.endswith((".jinja", ".j2", ".tpl", ".tmpl", ".template")):
                    full_path = os.path.join(root, file)
                    templates.append(os.path.relpath(full_path, target_dir).replace(os.sep, "/"))
        return templates

    def validate_template(self, template: str) -> tuple[bool, Optional[str]]:
        if self._env is None:
            return True, None
        try:
            self._env.parse(template)
            return True, None
        except Exception as e:
            return False, str(e)
