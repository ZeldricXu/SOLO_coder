import os
import re
import shutil
import fnmatch
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Set, Tuple

import inquirer
from jinja2 import Environment, Template


_SNAKE_CASE_PATTERN1 = re.compile(r"[\s\-]+")
_SNAKE_CASE_PATTERN2 = re.compile(r"([a-z0-9])([A-Z])")
_TEMPLATE_EXTENSIONS = {".j2", ".jinja2", ".jinja"}
_VARIABLE_PATTERN = re.compile(r"\{\{.*?\}\}|\{%.*?%\}")


class VariableType(str, Enum):
    STRING = "string"
    INTEGER = "integer"
    BOOLEAN = "boolean"
    CHOICE = "choice"
    LIST = "list"


@dataclass
class TemplateVariable:
    name: str
    type: VariableType = VariableType.STRING
    description: str = ""
    default: Any = None
    required: bool = True
    choices: List[str] = field(default_factory=list)
    validation: Optional[Callable[[Any], bool]] = None


@dataclass
class ProjectTemplate:
    name: str
    source_dir: Path
    description: str = ""
    version: str = "1.0.0"
    variables: List[TemplateVariable] = field(default_factory=list)
    ignore_patterns: List[str] = field(default_factory=lambda: [
        "__pycache__", "*.pyc", ".git", ".venv", "node_modules"
    ])
    post_generate_hooks: List[Callable] = field(default_factory=list)


def _snake_case(name: str) -> str:
    name = _SNAKE_CASE_PATTERN1.sub("_", name)
    name = _SNAKE_CASE_PATTERN2.sub(r"\1_\2", name)
    return name.lower().strip("_")


def _camel_case(name: str) -> str:
    snake = _snake_case(name)
    parts = snake.split("_")
    return parts[0] + "".join(p.capitalize() for p in parts[1:])


def _pascal_case(name: str) -> str:
    camel = _camel_case(name)
    return camel[0].upper() + camel[1:] if camel else ""


def _kebab_case(name: str) -> str:
    return _snake_case(name).replace("_", "-")


def _has_template_syntax(content: str) -> bool:
    return _VARIABLE_PATTERN.search(content) is not None


class TemplateEngine:
    _instance: Optional["TemplateEngine"] = None
    _instance_lock = object()

    def __init__(self):
        self.env = Environment(
            autoescape=False,
            trim_blocks=True,
            lstrip_blocks=True,
            keep_trailing_newline=True,
            cache_size=100
        )
        self.env.filters["camel_case"] = _camel_case
        self.env.filters["pascal_case"] = _pascal_case
        self.env.filters["snake_case"] = _snake_case
        self.env.filters["kebab_case"] = _kebab_case
        self.env.filters["upper_case"] = lambda s: s.upper()
        self.env.filters["lower_case"] = lambda s: s.lower()

        self._string_cache: Dict[str, Template] = {}

    @classmethod
    def get_instance(cls) -> "TemplateEngine":
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def render_string(self, template_str: str, context: Dict[str, Any]) -> str:
        if not template_str:
            return ""

        if not _has_template_syntax(template_str):
            return template_str

        template = self._string_cache.get(template_str)
        if template is None:
            if len(self._string_cache) > 500:
                self._string_cache.clear()
            template = self.env.from_string(template_str)
            self._string_cache[template_str] = template

        return template.render(**context)

    def render_file(self, source_path: Path, context: Dict[str, Any]) -> str:
        content = source_path.read_text(encoding="utf-8")
        return self.render_string(content, context)


def _compile_ignore_patterns(patterns: List[str]) -> Callable[[str], bool]:
    compiled = []
    for pattern in patterns:
        regex = fnmatch.translate(pattern)
        compiled.append(re.compile(regex))

    def matches(name: str) -> bool:
        for c in compiled:
            if c.match(name):
                return True
        return False

    return matches


class ProjectGenerator:
    _instance: Optional["ProjectGenerator"] = None

    def __init__(self):
        self.engine = TemplateEngine.get_instance()
        self.templates: Dict[str, ProjectTemplate] = {}
        self._ignore_matchers: Dict[str, Callable[[str], bool]] = {}

    @classmethod
    def get_instance(cls) -> "ProjectGenerator":
        if cls._instance is None:
            cls._instance = cls()
        return cls._instance

    def register_template(self, template: ProjectTemplate) -> None:
        self.templates[template.name] = template
        self._ignore_matchers[template.name] = _compile_ignore_patterns(
            template.ignore_patterns
        )

    def list_templates(self) -> List[Dict[str, str]]:
        return [
            {"name": t.name, "description": t.description, "version": t.version}
            for t in self.templates.values()
        ]

    def get_template(self, name: str) -> Optional[ProjectTemplate]:
        return self.templates.get(name)

    def validate_variables(
        self,
        template: ProjectTemplate,
        values: Dict[str, Any]
    ) -> Dict[str, str]:
        errors: Dict[str, str] = {}
        for var in template.variables:
            value = values.get(var.name)

            if value is None:
                if var.required and var.default is None:
                    errors[var.name] = f"变量 '{var.name}' 是必需的"
                continue

            if var.validation is not None and not var.validation(value):
                errors[var.name] = f"变量 '{var.name}' 验证失败"
                continue

            if var.type == VariableType.INTEGER:
                try:
                    int(value)
                except (ValueError, TypeError):
                    errors[var.name] = f"变量 '{var.name}' 必须是整数"
            elif var.type == VariableType.BOOLEAN:
                if not isinstance(value, bool):
                    errors[var.name] = f"变量 '{var.name}' 必须是布尔值"

        return errors

    def _should_ignore(self, name: str, matcher: Callable[[str], bool]) -> bool:
        return matcher(name)

    def _collect_files(
        self,
        source_dir: Path,
        matcher: Callable[[str], bool]
    ) -> List[Tuple[Path, Path]]:
        files: List[Tuple[Path, Path]] = []

        for root, dirs, filenames in os.walk(source_dir):
            dirs[:] = [d for d in dirs if not self._should_ignore(d, matcher)]

            rel_root = Path(root).relative_to(source_dir)

            for filename in filenames:
                if self._should_ignore(filename, matcher):
                    continue
                source_path = Path(root) / filename
                files.append((source_path, rel_root))

        return files

    def _process_file(
        self,
        source_path: Path,
        target_root: Path,
        context: Dict[str, Any],
        overwrite: bool,
        engine: TemplateEngine
    ) -> Optional[str]:
        filename = source_path.name

        target_name = engine.render_string(filename, context)
        target_path = target_root / target_name

        if target_path.exists() and not overwrite:
            return None

        is_template = source_path.suffix in _TEMPLATE_EXTENSIONS

        try:
            content = source_path.read_text(encoding="utf-8")

            if is_template or _has_template_syntax(content):
                rendered = engine.render_string(content, context)
                if is_template:
                    target_path = target_path.with_suffix("")
                target_path.write_text(rendered, encoding="utf-8")
            else:
                shutil.copy2(source_path, target_path)

            return str(target_path)

        except UnicodeDecodeError:
            shutil.copy2(source_path, target_path)
            return str(target_path)
        except (OSError, IOError) as e:
            raise RuntimeError(
                f"处理文件 {source_path} 失败: {e}"
            ) from e

    def generate(
        self,
        template_name: str,
        output_dir: Path,
        variables: Dict[str, Any],
        overwrite: bool = False
    ) -> Dict[str, Any]:
        template = self.get_template(template_name)
        if not template:
            raise ValueError(f"模板 '{template_name}' 不存在")

        errors = self.validate_variables(template, variables)
        if errors:
            raise ValueError(f"变量验证失败: {errors}")

        output_dir = Path(output_dir)
        if output_dir.exists() and not overwrite:
            try:
                next(output_dir.iterdir())
                raise ValueError(f"输出目录 '{output_dir}' 已存在且不为空")
            except StopIteration:
                pass

        output_dir.mkdir(parents=True, exist_ok=True)

        context = {
            **variables,
            "generator": {
                "template": template_name,
                "version": template.version,
                "generated_at": datetime.utcnow().isoformat()
            }
        }

        engine = self.engine
        matcher = self._ignore_matchers.get(
            template_name,
            _compile_ignore_patterns(template.ignore_patterns)
        )

        files_to_process = self._collect_files(template.source_dir, matcher)

        generated_files: List[str] = []
        output_dir_str = str(output_dir)

        for source_path, rel_root in files_to_process:
            rendered_rel_root = engine.render_string(str(rel_root), context)
            target_root = output_dir / rendered_rel_root
            target_root.mkdir(parents=True, exist_ok=True)

            result = self._process_file(
                source_path,
                target_root,
                context,
                overwrite,
                engine
            )

            if result is not None:
                rel_path = os.path.relpath(result, output_dir_str)
                generated_files.append(rel_path)

        for hook in template.post_generate_hooks:
            try:
                hook(output_dir, context)
            except Exception:
                pass

        return {
            "success": True,
            "template": template_name,
            "output_dir": output_dir_str,
            "files_generated": len(generated_files),
            "files": generated_files,
            "variables": variables
        }

    def interactive_prompt(
        self,
        template: ProjectTemplate,
        defaults: Optional[Dict[str, Any]] = None
    ) -> Dict[str, Any]:
        defaults = defaults or {}
        questions: List[Any] = []

        for var in template.variables:
            default_value = defaults.get(var.name, var.default)

            if var.type == VariableType.BOOLEAN:
                questions.append(inquirer.Confirm(
                    var.name,
                    message=var.description or var.name,
                    default=bool(default_value) if default_value is not None else False
                ))
            elif var.type == VariableType.CHOICE and var.choices:
                questions.append(inquirer.List(
                    var.name,
                    message=var.description or var.name,
                    choices=var.choices,
                    default=default_value
                ))
            elif var.type == VariableType.INTEGER:
                questions.append(inquirer.Text(
                    var.name,
                    message=var.description or var.name,
                    default=str(default_value) if default_value is not None else "",
                    validate=lambda _, x: x.isdigit() if var.required else True
                ))
            else:
                questions.append(inquirer.Text(
                    var.name,
                    message=var.description or var.name,
                    default=default_value,
                    validate=lambda _, x: bool(x.strip()) if var.required else True
                ))

        answers = inquirer.prompt(questions) or {}
        result: Dict[str, Any] = {}

        for var in template.variables:
            value = answers.get(var.name, var.default)

            if var.type == VariableType.INTEGER and value:
                try:
                    value = int(value)
                except (ValueError, TypeError):
                    value = var.default

            result[var.name] = value

        return result


def create_builtin_template(template_dir: Path) -> ProjectTemplate:
    return ProjectTemplate(
        name="fastapi-service",
        description="FastAPI 微服务模板",
        version="1.0.0",
        source_dir=template_dir,
        variables=[
            TemplateVariable(
                name="project_name",
                type=VariableType.STRING,
                description="项目名称",
                default="my-service"
            ),
            TemplateVariable(
                name="package_name",
                type=VariableType.STRING,
                description="Python 包名",
                default="my_service"
            ),
            TemplateVariable(
                name="author",
                type=VariableType.STRING,
                description="作者名称",
                required=False
            ),
            TemplateVariable(
                name="description",
                type=VariableType.STRING,
                description="项目描述",
                default="A FastAPI microservice"
            ),
            TemplateVariable(
                name="use_database",
                type=VariableType.BOOLEAN,
                description="是否使用数据库",
                default=True
            ),
            TemplateVariable(
                name="use_read_write_splitting",
                type=VariableType.BOOLEAN,
                description="是否启用数据库读写分离",
                default=False
            ),
            TemplateVariable(
                name="read_write_strategy",
                type=VariableType.CHOICE,
                description="读写分离路由策略",
                default="auto",
                choices=["auto", "primary_only", "replica_only", "read_only"]
            ),
            TemplateVariable(
                name="replica_count",
                type=VariableType.INTEGER,
                description="只读副本数量",
                default=1
            ),
            TemplateVariable(
                name="use_redis",
                type=VariableType.BOOLEAN,
                description="是否使用 Redis",
                default=False
            ),
            TemplateVariable(
                name="use_metrics_plugins",
                type=VariableType.BOOLEAN,
                description="是否启用监控插件",
                default=False
            )
        ]
    )


def get_generator() -> ProjectGenerator:
    return ProjectGenerator.get_instance()


def generate_project(
    template_name: str,
    output_dir: str,
    variables: Dict[str, Any],
    overwrite: bool = False
) -> Dict[str, Any]:
    generator = get_generator()
    return generator.generate(template_name, Path(output_dir), variables, overwrite)


def interactive_generate(
    template_name: str,
    output_dir: str,
    defaults: Optional[Dict[str, Any]] = None,
    overwrite: bool = False
) -> Dict[str, Any]:
    generator = get_generator()
    template = generator.get_template(template_name)
    if not template:
        raise ValueError(f"模板 '{template_name}' 不存在")
    variables = generator.interactive_prompt(template, defaults)
    return generator.generate(template_name, Path(output_dir), variables, overwrite)
