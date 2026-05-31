import asyncio
import json
import os
import re
import shutil
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple, Type

import yaml
from jinja2 import Environment, FileSystemLoader, Template as JinjaTemplate, select_autoescape

from src.config import get_settings
from src.logging_ import get_logger
from src.utils.errors import ConfigurationError, ValidationError
from src.utils.helpers import deep_merge, sanitize_dict

logger = get_logger(__name__)


@dataclass
class TemplateVariable:
    name: str
    type: str = "string"
    description: Optional[str] = None
    default: Optional[Any] = None
    required: bool = True
    choices: Optional[List[Any]] = None
    validation: Optional[str] = None


@dataclass
class Template:
    template_id: str
    name: str
    description: str
    version: str = "1.0.0"
    variables: List[TemplateVariable] = field(default_factory=list)
    tags: List[str] = field(default_factory=list)
    directory: str = "default"


@dataclass
class GeneratedFile:
    path: str
    content: str
    template_source: Optional[str] = None
    is_binary: bool = False


class TemplateManager:
    def __init__(self, templates_dir: Optional[str] = None):
        self.settings = get_settings()
        self.templates_dir = Path(templates_dir or "./templates")
        self.templates_dir.mkdir(parents=True, exist_ok=True)
        self._templates: Dict[str, Template] = {}
        self._load_builtin_templates()

    @staticmethod
    def _convert_boolean(value: Any) -> bool:
        if isinstance(value, bool):
            return value
        return str(value).lower() in ("true", "1", "yes")

    @staticmethod
    def _convert_number(value: Any) -> float:
        str_value = str(value)
        return float(str_value) if "." in str_value else int(str_value)

    @staticmethod
    def _validate_choices(value: Any, choices: List[Any]) -> bool:
        return value in choices

    @staticmethod
    def _validate_format(value: str, pattern: str) -> bool:
        return bool(re.match(pattern, value))

    def _load_builtin_templates(self) -> None:
        self._templates["python-service"] = Template(
            template_id="python-service",
            name="Python Microservice",
            description="标准Python微服务项目模板，包含FastAPI、SQLAlchemy等",
            version="1.0.0",
            tags=["python", "fastapi", "microservice"],
            variables=[
                TemplateVariable(
                    name="project_name",
                    type="string",
                    description="项目名称",
                    required=True,
                    validation=r"^[a-z][a-z0-9-]+$",
                ),
                TemplateVariable(
                    name="author",
                    type="string",
                    description="作者名称",
                    default="Developer",
                    required=False,
                ),
                TemplateVariable(
                    name="python_version",
                    type="string",
                    description="Python版本",
                    default="3.10",
                    choices=["3.9", "3.10", "3.11", "3.12"],
                    required=False,
                ),
                TemplateVariable(
                    name="use_database",
                    type="boolean",
                    description="是否使用数据库",
                    default=True,
                    required=False,
                ),
                TemplateVariable(
                    name="database_type",
                    type="string",
                    description="数据库类型",
                    default="postgresql",
                    choices=["postgresql", "mysql", "sqlite"],
                    required=False,
                ),
                TemplateVariable(
                    name="use_redis",
                    type="boolean",
                    description="是否使用Redis",
                    default=False,
                    required=False,
                ),
                TemplateVariable(
                    name="use_docker",
                    type="boolean",
                    description="是否生成Docker配置",
                    default=True,
                    required=False,
                ),
                TemplateVariable(
                    name="license",
                    type="string",
                    description="开源协议",
                    default="MIT",
                    choices=["MIT", "Apache-2.0", "GPL-3.0", "Proprietary"],
                    required=False,
                ),
            ],
            directory="python-service",
        )

        self._templates["python-cli"] = Template(
            template_id="python-cli",
            name="Python CLI Tool",
            description="Python命令行工具项目模板",
            version="1.0.0",
            tags=["python", "cli"],
            variables=[
                TemplateVariable(
                    name="project_name",
                    type="string",
                    description="项目名称",
                    required=True,
                    validation=r"^[a-z][a-z0-9-]+$",
                ),
                TemplateVariable(
                    name="command_name",
                    type="string",
                    description="CLI命令名称",
                    required=True,
                ),
                TemplateVariable(
                    name="use_click",
                    type="boolean",
                    description="是否使用Click框架",
                    default=True,
                    required=False,
                ),
            ],
            directory="python-cli",
        )

        self._templates["python-library"] = Template(
            template_id="python-library",
            name="Python Library",
            description="Python库项目模板",
            version="1.0.0",
            tags=["python", "library"],
            variables=[
                TemplateVariable(
                    name="library_name",
                    type="string",
                    description="库名称",
                    required=True,
                ),
                TemplateVariable(
                    name="module_name",
                    type="string",
                    description="Python模块名",
                    required=True,
                ),
            ],
            directory="python-library",
        )

        self._templates["fastapi-basic"] = Template(
            template_id="fastapi-basic",
            name="FastAPI Basic",
            description="基础FastAPI项目模板",
            version="1.0.0",
            tags=["python", "fastapi"],
            variables=[
                TemplateVariable(
                    name="app_name",
                    type="string",
                    description="应用名称",
                    required=True,
                ),
                TemplateVariable(
                    name="api_version",
                    type="string",
                    description="API版本",
                    default="v1",
                    required=False,
                ),
            ],
            directory="fastapi-basic",
        )

    def list_templates(self, tags: Optional[List[str]] = None) -> List[Template]:
        if not tags:
            return list(self._templates.values())
        return [
            t for t in self._templates.values()
            if any(tag in t.tags for tag in tags)
        ]

    def get_template(self, template_id: str) -> Optional[Template]:
        return self._templates.get(template_id)

    def register_template(self, template: Template, template_files: Dict[str, str]) -> None:
        template_dir = self.templates_dir / template.directory
        template_dir.mkdir(parents=True, exist_ok=True)

        for file_path, content in template_files.items():
            full_path = template_dir / file_path
            full_path.parent.mkdir(parents=True, exist_ok=True)
            full_path.write_text(content)

        self._templates[template.template_id] = template
        logger.info("Registered template: %s", template.name)

    def _validate_variable(
        self,
        var: TemplateVariable,
        variables: Dict[str, Any],
        errors: List[str],
    ) -> None:
        if var.name not in variables:
            if var.default is not None:
                variables[var.name] = var.default
            elif var.required:
                errors.append(f"Missing required variable: {var.name}")
            return

        value = variables[var.name]

        try:
            if var.type == "boolean":
                variables[var.name] = self._convert_boolean(value)
            elif var.type == "number":
                variables[var.name] = self._convert_number(value)
        except Exception:
            errors.append(f"Invalid {var.type} value for: {var.name}")
            return

        if var.choices and not self._validate_choices(variables[var.name], var.choices):
            errors.append(
                f"Invalid value for {var.name}. Must be one of: {var.choices}"
            )

        if var.validation and isinstance(variables[var.name], str):
            if not self._validate_format(variables[var.name], var.validation):
                errors.append(f"Invalid format for {var.name}: {var.validation}")

    def validate_variables(
        self,
        template: Template,
        variables: Dict[str, Any],
    ) -> Tuple[bool, List[str]]:
        errors: List[str] = []

        for var in template.variables:
            self._validate_variable(var, variables, errors)

        return len(errors) == 0, errors


class InteractivePrompter:
    COLORS = {
        "cyan": "\033[96m",
        "green": "\033[92m",
        "yellow": "\033[93m",
        "red": "\033[91m",
        "bold": "\033[1m",
        "reset": "\033[0m",
    }

    def __init__(self, use_colors: bool = True):
        self.use_colors = use_colors
        self.input_fn = input
        self.print_fn = print

    def _colorize(self, text: str, color: str) -> str:
        if not self.use_colors:
            return text
        return f"{self.COLORS.get(color, '')}{text}{self.COLORS['reset']}"

    def _print_error(self, message: str) -> None:
        self.print_fn(self._colorize(message, "red"))

    def _format_prompt(
        self,
        message: str,
        default: Optional[Any] = None,
        choices: Optional[List[Any]] = None,
    ) -> str:
        prompt_msg = message
        if default is not None:
            prompt_msg += f" [{default}]"
        if choices:
            prompt_msg += f" ({', '.join(str(c) for c in choices)})"
        return prompt_msg + ": "

    def prompt(
        self,
        message: str,
        default: Optional[Any] = None,
        required: bool = True,
        choices: Optional[List[Any]] = None,
        validation: Optional[str] = None,
    ) -> Any:
        while True:
            colored_prompt = self._colorize(
                self._format_prompt(message, default, choices),
                "cyan",
            )
            user_input = self.input_fn(colored_prompt).strip()

            if not user_input and default is not None:
                return default

            if not user_input and required:
                self._print_error("This field is required")
                continue

            if choices and user_input not in [str(c) for c in choices]:
                self._print_error(
                    f"Invalid choice. Must be one of: {', '.join(str(c) for c in choices)}"
                )
                continue

            if validation and not re.match(validation, user_input):
                self._print_error(f"Invalid format: {validation}")
                continue

            return user_input

    def prompt_confirm(self, message: str, default: bool = True) -> bool:
        while True:
            default_str = "Y/n" if default else "y/N"
            prompt_msg = f"{message} [{default_str}]: "
            user_input = self.input_fn(self._colorize(prompt_msg, "cyan")).strip().lower()

            if not user_input:
                return default

            if user_input in ("y", "yes", "true", "1"):
                return True
            if user_input in ("n", "no", "false", "0"):
                return False

            self._print_error("Please enter 'y' or 'n'")

    def prompt_select(
        self,
        message: str,
        options: List[Tuple[str, Any]],
        default: Optional[int] = None,
    ) -> Any:
        self.print_fn(self._colorize(message, "bold"))
        for i, (label, _) in enumerate(options, 1):
            prefix = "* " if default is not None and i == default + 1 else "  "
            self.print_fn(f"{prefix}{i}. {label}")

        while True:
            prompt_msg = "Select option"
            if default is not None:
                prompt_msg += f" [{default + 1}]"
            prompt_msg += ": "

            user_input = self.input_fn(self._colorize(prompt_msg, "cyan")).strip()

            if not user_input and default is not None:
                return options[default][1]

            try:
                idx = int(user_input) - 1
                if 0 <= idx < len(options):
                    return options[idx][1]
            except ValueError:
                pass

            self._print_error(f"Please enter a number between 1 and {len(options)}")

    async def prompt_for_template(
        self,
        template: Template,
        defaults: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        defaults = defaults or {}
        variables: Dict[str, Any] = {}

        self.print_fn(
            self._colorize(
                f"\n{'='*60}\nConfiguring template: {template.name}\n{'='*60}",
                "bold",
            )
        )

        for var in template.variables:
            default_value = defaults.get(var.name, var.default)

            if var.type == "boolean":
                value = self.prompt_confirm(
                    var.description or var.name,
                    default=bool(default_value) if default_value is not None else True,
                )
            elif var.choices:
                options = [(str(c), c) for c in var.choices]
                default_idx = var.choices.index(default_value) if default_value in var.choices else None
                value = self.prompt_select(
                    var.description or var.name,
                    options,
                    default=default_idx,
                )
            else:
                value = self.prompt(
                    var.description or var.name,
                    default=default_value,
                    required=var.required,
                    choices=var.choices,
                    validation=var.validation,
                )

            variables[var.name] = value
            await asyncio.sleep(0.01)

        return variables


class ProjectScaffolder:
    CASE_FUNCTIONS = {
        "camel_case": "_camel_case",
        "pascal_case": "_pascal_case",
        "snake_case": "_snake_case",
        "kebab_case": "_kebab_case",
    }

    def __init__(
        self,
        templates_dir: Optional[str] = None,
        output_dir: Optional[str] = None,
    ):
        self.template_manager = TemplateManager(templates_dir)
        self.output_dir = Path(output_dir or "./generated")
        self.output_dir.mkdir(parents=True, exist_ok=True)

        self.jinja_env = Environment(
            loader=FileSystemLoader(str(self.template_manager.templates_dir)),
            autoescape=select_autoescape(["html", "xml"]),
            trim_blocks=True,
            lstrip_blocks=True,
            keep_trailing_newline=True,
        )

        self._register_filters()

    def _register_filters(self) -> None:
        for filter_name, method_name in self.CASE_FUNCTIONS.items():
            self.jinja_env.filters[filter_name] = getattr(self, method_name)
        self.jinja_env.filters["upper_first"] = lambda s: s[0].upper() + s[1:] if s else s

    @staticmethod
    def _camel_case(text: str) -> str:
        text = re.sub(r"[^a-zA-Z0-9]", " ", text)
        words = re.sub(r"([A-Z])", r" \1", text).split()
        if not words:
            return text.lower().replace(" ", "")
        return words[0].lower() + "".join(w.capitalize() for w in words[1:])

    @staticmethod
    def _pascal_case(text: str) -> str:
        text = re.sub(r"[^a-zA-Z0-9]", " ", text)
        words = re.sub(r"([A-Z])", r" \1", text).split()
        return "".join(w.capitalize() for w in words)

    @staticmethod
    def _snake_case(text: str) -> str:
        text = re.sub(r"[^a-zA-Z0-9]", "_", text)
        text = re.sub(r"([A-Z])", r"_\1", text)
        text = re.sub(r"_+", "_", text)
        return text.lower().strip("_")

    @staticmethod
    def _kebab_case(text: str) -> str:
        text = re.sub(r"[^a-zA-Z0-9]", "-", text)
        text = re.sub(r"([A-Z])", r"-\1", text)
        text = re.sub(r"-+", "-", text)
        return text.lower().strip("-")

    @staticmethod
    def _normalize_path(path: str) -> str:
        return path.replace("\\", "/")

    @staticmethod
    def _backup_existing_dir(project_dir: Path) -> None:
        if project_dir.exists():
            backup_dir = Path(f"{project_dir}.{int(time.time())}.bak")
            project_dir.rename(backup_dir)
            logger.info("Backed up existing directory to %s", backup_dir)

    @staticmethod
    def _write_file(file_path: Path, content: str, is_binary: bool = False) -> None:
        file_path.parent.mkdir(parents=True, exist_ok=True)
        if is_binary:
            file_path.write_bytes(content.encode("latin1"))
        else:
            file_path.write_text(content, encoding="utf-8")
        logger.info("Generated: %s", file_path)

    def _get_template_files(self, template_dir: str) -> List[Path]:
        template_path = self.template_manager.templates_dir / template_dir
        if not template_path.exists():
            return []
        return [p for p in template_path.rglob("*") if p.is_file()]

    def _render_template(
        self,
        template_path: Path,
        template_rel_path: str,
        output_rel_path: str,
        variables: Dict[str, Any],
    ) -> GeneratedFile:
        try:
            rendered_path = self.jinja_env.from_string(output_rel_path).render(variables)
        except Exception:
            rendered_path = output_rel_path

        try:
            template = self.jinja_env.get_template(template_rel_path)
            content = template.render(**variables)
        except Exception as e:
            logger.warning("Failed to render template %s: %s, using raw content", template_rel_path, e)
            content = template_path.read_text(encoding="utf-8", errors="replace")

        if rendered_path.endswith(".j2"):
            rendered_path = rendered_path[:-3]

        return GeneratedFile(
            path=rendered_path,
            content=content,
            template_source=template_rel_path,
        )

    def _generate_files(
        self,
        template: Template,
        variables: Dict[str, Any],
        extra_files: Optional[List[GeneratedFile]] = None,
    ) -> List[GeneratedFile]:
        generated_files: List[GeneratedFile] = []
        template_files = self._get_template_files(template.directory)

        for template_file in template_files:
            rel_path = template_file.relative_to(
                self.template_manager.templates_dir
            ).as_posix()

            try:
                generated = self._render_template(
                    template_file,
                    rel_path,
                    rel_path[len(template.directory) + 1 :],
                    variables,
                )
                generated_files.append(generated)
            except Exception as e:
                logger.error("Failed to process %s: %s", template_file, e)

        if extra_files:
            generated_files.extend(extra_files)

        return generated_files

    def _create_manifest(
        self,
        template: Template,
        variables: Dict[str, Any],
        generated_files: List[GeneratedFile],
    ) -> Dict[str, Any]:
        return {
            "template_id": template.template_id,
            "template_name": template.name,
            "template_version": template.version,
            "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ"),
            "variables": sanitize_dict(variables),
            "files": [
                {"path": f.path, "template": f.template_source}
                for f in generated_files
            ],
        }

    def _write_manifest(
        self,
        project_dir: Path,
        template: Template,
        variables: Dict[str, Any],
        generated_files: List[GeneratedFile],
    ) -> None:
        manifest = self._create_manifest(template, variables, generated_files)
        manifest_path = project_dir / ".scaffold-manifest.json"
        manifest_path.write_text(json.dumps(manifest, indent=2), encoding="utf-8")

    def generate_from_template(
        self,
        template_id: str,
        variables: Dict[str, Any],
        output_subdir: Optional[str] = None,
        extra_files: Optional[List[GeneratedFile]] = None,
    ) -> Tuple[Path, List[GeneratedFile]]:
        template = self.template_manager.get_template(template_id)
        if not template:
            raise ValidationError(f"Template not found: {template_id}")

        is_valid, errors = self.template_manager.validate_variables(template, variables)
        if not is_valid:
            raise ValidationError(
                f"Invalid variables for template {template_id}: {'; '.join(errors)}",
                {"errors": errors},
            )

        generated_files = self._generate_files(template, variables, extra_files)

        project_dir = self.output_dir / (output_subdir or variables.get("project_name", template_id))
        self._backup_existing_dir(project_dir)
        project_dir.mkdir(parents=True, exist_ok=True)

        for gen_file in generated_files:
            file_path = project_dir / gen_file.path
            self._write_file(file_path, gen_file.content, gen_file.is_binary)

        self._write_manifest(project_dir, template, variables, generated_files)

        return project_dir, generated_files

    async def interactive_generate(
        self,
        template_id: Optional[str] = None,
        output_dir: Optional[str] = None,
    ) -> Tuple[Path, List[GeneratedFile]]:
        prompter = InteractivePrompter()

        if not template_id:
            templates = self.template_manager.list_templates()
            options = [(t.name, t.template_id) for t in templates]
            template_id = await prompter.prompt_select(
                "Available templates:",
                options,
                default=0,
            )

        template = self.template_manager.get_template(template_id)
        if not template:
            raise ValidationError(f"Template not found: {template_id}")

        variables = await prompter.prompt_for_template(template)

        prompter.print_fn(
            prompter._colorize("\nConfiguration summary:", "bold")
        )
        for key, value in variables.items():
            prompter.print_fn(f"  {key}: {value}")

        if not prompter.prompt_confirm("\nProceed with generation?", default=True):
            raise ConfigurationError("Generation cancelled by user")

        return self.generate_from_template(
            template_id,
            variables,
            output_subdir=output_dir,
        )

    def list_templates(self, tags: Optional[List[str]] = None) -> List[Template]:
        return self.template_manager.list_templates(tags)

    def get_template_info(self, template_id: str) -> Optional[Dict[str, Any]]:
        template = self.template_manager.get_template(template_id)
        if not template:
            return None
        return {
            "template_id": template.template_id,
            "name": template.name,
            "description": template.description,
            "version": template.version,
            "tags": template.tags,
            "variables": [
                {
                    "name": v.name,
                    "type": v.type,
                    "description": v.description,
                    "default": v.default,
                    "required": v.required,
                    "choices": v.choices,
                }
                for v in template.variables
            ],
        }
