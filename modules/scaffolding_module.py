import os
import re
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
from enum import Enum
from .logging_module import get_logger

logger = get_logger(__name__)


class ProjectType(str, Enum):
    FASTAPI = "fastapi"
    CLI = "cli"
    LIBRARY = "library"


@dataclass
class TemplateConfig:
    name: str
    template_type: ProjectType
    variables: Dict[str, Any] = field(default_factory=dict)
    author: str = "Anonymous"
    version: str = "0.1.0"
    description: str = ""
    use_docker: bool = False
    use_tests: bool = True
    use_ci: bool = False


@dataclass
class GeneratedFile:
    path: str
    content: str


@dataclass
class GeneratedProject:
    project_path: str
    files: List[GeneratedFile] = field(default_factory=list)
    template_type: ProjectType = ProjectType.FASTAPI


class TemplateEngine:
    def __init__(self):
        self._templates: Dict[str, Dict[str, str]] = {}
        self._init_templates()

    def _init_templates(self):
        self._templates[ProjectType.FASTAPI] = {
            "requirements.txt": "fastapi>=0.109.0\nuvicorn>=0.27.0\npydantic>=2.5.0\n",
            "main.py": """from fastapi import FastAPI
app = FastAPI(title="{{ project_name }}", version="{{ version }}")
@app.get("/")
async def root():
    return {"message": "Hello from {{ project_name }}"}
@app.get("/health")
async def health_check():
    return {"status": "healthy"}
""",
            "README.md": "# {{ project_name }}\n\n{{ description }}\n\n## Install\n\n```bash\npip install -r requirements.txt\n```\n\n## Run\n\n```bash\nuvicorn main:app --reload\n```\n",
        }
        self._templates[ProjectType.CLI] = {
            "main.py": """#!/usr/bin/env python3
\"\"\"{{ project_name }}\"\"\"
import argparse
import sys

def main():
    parser = argparse.ArgumentParser(description="{{ description }}")
    parser.add_argument("--version", action="version", version="{{ version }}")
    parser.add_argument("input", help="Input file")
    args = parser.parse_args()
    print(f"Processing {args.input}...")
    return 0

if __name__ == "__main__":
    sys.exit(main())
""",
            "README.md": "# {{ project_name }}\n\n{{ description }}\n",
        }
        self._templates[ProjectType.LIBRARY] = {
            "src/__init__.py": "__version__ = \"{{ version }}\"\n__author__ = \"{{ author }}\"\n",
            "README.md": "# {{ project_name }}\n\n{{ description }}\n",
        }

    def get_template(self, template_type: ProjectType) -> Dict[str, str]:
        return self._templates.get(template_type, {})


class VariableProcessor:
    @staticmethod
    def process(template: str, variables: Dict[str, Any]) -> str:
        result = template
        for key, value in variables.items():
            placeholder = "{{ " + key + " }}"
            if placeholder in result:
                result = result.replace(placeholder, str(value))
        result = re.sub(r'\{\{\s*(\w+)\s*\}\}', '', result)
        return result


class ProjectScaffolder:
    def __init__(self):
        self.template_engine = TemplateEngine()
        self.processor = VariableProcessor()

    def generate(self, config: TemplateConfig) -> GeneratedProject:
        template = self.template_engine.get_template(config.template_type)
        if not template:
            raise ValueError(f"No template found for type: {config.template_type}")

        variables = {
            "project_name": config.name,
            "version": config.version,
            "author": config.author,
            "description": config.description,
            **config.variables,
        }

        generated_files = []
        for file_path, file_template in template.items():
            processed = self.processor.process(file_template, variables)
            generated_files.append(GeneratedFile(path=file_path, content=processed))

        logger.info(f"Generated project: {config.name} ({config.template_type}) with {len(generated_files)} files")
        return GeneratedProject(project_path=config.name, files=generated_files, template_type=config.template_type)

    def write_to_disk(self, project: GeneratedProject, output_dir: str = ".") -> str:
        base_path = os.path.join(output_dir, project.project_path)
        os.makedirs(base_path, exist_ok=True)
        for file in project.files:
            file_path = os.path.join(base_path, file.path)
            os.makedirs(os.path.dirname(file_path), exist_ok=True)
            with open(file_path, "w", encoding="utf-8") as f:
                f.write(file.content)
        logger.info(f"Project written to: {base_path}")
        return base_path

    def list_templates(self) -> Dict[str, List[str]]:
        result = {}
        for pt in ProjectType:
            template = self.template_engine.get_template(pt)
            result[pt.value] = list(template.keys())
        return result


_scaffolder: Optional[ProjectScaffolder] = None


def get_scaffolder() -> ProjectScaffolder:
    global _scaffolder
    if _scaffolder is None:
        _scaffolder = ProjectScaffolder()
    return _scaffolder
