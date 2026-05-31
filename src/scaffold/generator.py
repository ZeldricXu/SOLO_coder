from __future__ import annotations

import logging
import os
import shutil
import time
from typing import Any, Dict, List, Optional

from src.scaffold.models import GenerationResult, ProjectConfig, TemplateDefinition
from src.scaffold.templates import TemplateEngine, TemplateRepository

logger = logging.getLogger(__name__)


class ProjectGenerator:
    def __init__(
        self,
        template_engine: Optional[TemplateEngine] = None,
        template_repo: Optional[TemplateRepository] = None,
    ) -> None:
        self.template_engine = template_engine or TemplateEngine()
        self.template_repo = template_repo or TemplateRepository()

    def generate(self, config: ProjectConfig) -> GenerationResult:
        start_time = time.time()
        errors: List[str] = []
        warnings: List[str] = []
        files_generated = 0
        files_skipped = 0

        template = self.template_repo.get(config.template_id)
        if not template:
            return GenerationResult(
                success=False,
                project_name=config.name,
                output_dir=config.output_dir,
                files_generated=0,
                files_skipped=0,
                errors=[f"Template '{config.template_id}' not found"],
                duration_ms=(time.time() - start_time) * 1000,
            )

        context = self._build_context(config, template)

        try:
            os.makedirs(config.output_dir, exist_ok=True)
        except Exception as e:
            return GenerationResult(
                success=False,
                project_name=config.name,
                output_dir=config.output_dir,
                files_generated=0,
                files_skipped=0,
                errors=[f"Failed to create output directory: {e}"],
                duration_ms=(time.time() - start_time) * 1000,
            )

        for file_def in template.files:
            try:
                target_path = self.template_engine.render_string(file_def.target_path, context)
                full_target_path = os.path.join(config.output_dir, target_path)

                if os.path.exists(full_target_path) and not config.overwrite:
                    files_skipped += 1
                    warnings.append(f"Skipped existing file: {target_path}")
                    continue

                os.makedirs(os.path.dirname(full_target_path), exist_ok=True)

                if file_def.template:
                    content = self.template_engine.render_string(
                        self._get_default_content(file_def, template), context
                    )
                else:
                    content = self._get_default_content(file_def, template)

                with open(full_target_path, "w", encoding="utf-8") as f:
                    f.write(content)

                if file_def.executable:
                    os.chmod(full_target_path, 0o755)

                files_generated += 1
                logger.info(f"Generated: {target_path}")

            except Exception as e:
                errors.append(f"Failed to generate {file_def.target_path}: {e}")

        success = len(errors) == 0
        duration_ms = (time.time() - start_time) * 1000

        if success:
            logger.info(f"Project '{config.name}' generated successfully in {duration_ms:.2f}ms")
        else:
            logger.warning(f"Project generation completed with {len(errors)} errors")

        return GenerationResult(
            success=success,
            project_name=config.name,
            output_dir=config.output_dir,
            files_generated=files_generated,
            files_skipped=files_skipped,
            errors=errors,
            warnings=warnings,
            duration_ms=duration_ms,
        )

    def _build_context(self, config: ProjectConfig, template: TemplateDefinition) -> Dict[str, Any]:
        context: Dict[str, Any] = {
            "project_name": config.name,
            "description": config.description,
            "author": config.author,
            "email": config.email,
            "version": config.version,
            "year": time.strftime("%Y"),
            "datetime": time.strftime("%Y-%m-%d %H:%M:%S"),
        }
        context.update(config.variables)
        return context

    def _get_default_content(self, file_def, template: TemplateDefinition) -> str:
        default_contents = {
            "fastapi/pyproject.toml.j2": self._get_fastapi_pyproject_content(),
            "fastapi/requirements.txt.j2": self._get_fastapi_requirements_content(),
            "fastapi/main.py.j2": self._get_fastapi_main_content(),
            "fastapi/README.md.j2": self._get_fastapi_readme_content(),
            "fastapi/.env.example.j2": self._get_fastapi_env_content(),
            "fastapi/.gitignore.j2": self._get_gitignore_content(),
            "fastapi/app/__init__.py.j2": '"""{{ project_name }} application package."""\n\n__version__ = "{{ version }}"\n',
            "fastapi/app/config.py.j2": self._get_fastapi_config_content(),
            "fastapi/app/models.py.j2": self._get_fastapi_models_content(),
            "fastapi/app/schemas.py.j2": self._get_fastapi_schemas_content(),
            "fastapi/app/routers/__init__.py.j2": "from .items import router as items_router\n\n__all__ = [\"items_router\"]\n",
            "fastapi/app/routers/items.py.j2": self._get_fastapi_router_content(),
            "fastapi/tests/__init__.py.j2": "",
            "fastapi/tests/conftest.py.j2": self._get_fastapi_conftest_content(),
            "library/pyproject.toml.j2": self._get_library_pyproject_content(),
            "library/README.md.j2": self._get_library_readme_content(),
            "library/.gitignore.j2": self._get_gitignore_content(),
            "library/src/__init__.py.j2": '"""{{ library_name }} package."""\n\n__version__ = "{{ version }}"\n',
            "library/src/core.py.j2": self._get_library_core_content(),
            "library/tests/__init__.py.j2": "",
            "library/tests/test_core.py.j2": self._get_library_test_content(),
            "cli/pyproject.toml.j2": self._get_cli_pyproject_content(),
            "cli/README.md.j2": self._get_cli_readme_content(),
            "cli/.gitignore.j2": self._get_gitignore_content(),
            "cli/cli.py.j2": self._get_cli_main_content(),
            "cli/commands/__init__.py.j2": "from .greet import greet\n\n__all__ = [\"greet\"]\n",
            "cli/commands/greet.py.j2": self._get_cli_command_content(),
        }
        return default_contents.get(file_def.source_path, "# Template content\n")

    def _get_fastapi_pyproject_content(self) -> str:
        return '''[build-system]
requires = ["setuptools>=68.0", "wheel"]
build-backend = "setuptools.build_meta"

[project]
name = "{{ project_name | to_kebab_case }}"
version = "{{ version }}"
description = "{{ description }}"
readme = "README.md"
requires-python = ">=3.10"
authors = [{ name = "{{ author }}", email = "{{ email }}" }]
dependencies = [
    "fastapi>=0.104.0",
    "uvicorn[standard]>=0.24.0",
    "pydantic>=2.5.0",
    {% if include_auth %}"python-jose[cryptography]>=3.3.0",
    "passlib[bcrypt]>=1.7.4",{% endif %}
    {% if database != "none" %}"sqlalchemy>=2.0.23",
    "alembic>=1.12.0",{% endif %}
]

[project.optional-dependencies]
dev = [
    "pytest>=7.4.0",
    "pytest-asyncio>=0.21.0",
    "pytest-cov>=4.1.0",
    "httpx>=0.25.0",
]

[project.scripts]
{{ project_name | to_snake_case }} = "main:app"
'''

    def _get_fastapi_requirements_content(self) -> str:
        return '''fastapi>=0.104.0
uvicorn[standard]>=0.24.0
pydantic>=2.5.0
{% if include_auth %}python-jose[cryptography]>=3.3.0
passlib[bcrypt]>=1.7.4{% endif %}
{% if database != "none" %}sqlalchemy>=2.0.23
alembic>=1.12.0{% endif %}
pytest>=7.4.0
pytest-asyncio>=0.21.0
httpx>=0.25.0
'''

    def _get_fastapi_main_content(self) -> str:
        return '''from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.routers import items_router

app = FastAPI(
    title="{{ project_name | to_python_class }}",
    description="{{ description }}",
    version="{{ version }}",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(items_router, prefix="/api/v1/items", tags=["Items"])


@app.get("/")
async def root():
    return {"message": "Welcome to {{ project_name | to_python_class }}", "version": "{{ version }}"}


@app.get("/health")
async def health_check():
    return {"status": "healthy"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run("main:app", host="0.0.0.0", port=8000, reload=True)
'''

    def _get_fastapi_readme_content(self) -> str:
        return '''# {{ project_name | to_python_class }}

{{ description }}

## Installation

```bash
pip install -r requirements.txt
```

## Usage

```bash
python main.py
```

## API Documentation

- Swagger UI: http://localhost:8000/docs
- ReDoc: http://localhost:8000/redoc

## Development

```bash
pytest tests/ -v
```

## License

Copyright (c) {{ year }} {{ author }}
'''

    def _get_fastapi_env_content(self) -> str:
        return '''APP_ENV=development
APP_HOST=0.0.0.0
APP_PORT=8000
DEBUG=true

{% if database != "none" %}DATABASE_URL={{ database }}+asyncpg://user:password@localhost:5432/{{ project_name | to_snake_case }}
{% endif %}
{% if include_auth %}JWT_SECRET_KEY=your-secret-key-change-in-production
JWT_ALGORITHM=HS256
JWT_ACCESS_TOKEN_EXPIRE_MINUTES=30
{% endif %}
'''

    def _get_gitignore_content(self) -> str:
        return '''__pycache__/
*.py[cod]
*$py.class
*.so
.Python
build/
develop-eggs/
dist/
downloads/
eggs/
.eggs/
lib/
lib64/
parts/
sdist/
var/
*.egg-info/
.installed.cfg
*.egg
.env
.env.local
*.db
*.sqlite
.pytest_cache/
.mypy_cache/
.ruff_cache/
.coverage
.coverage.*
*.log
logs/
'''

    def _get_fastapi_config_content(self) -> str:
        return '''from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_env: str = "development"
    app_host: str = "0.0.0.0"
    app_port: int = 8000
    debug: bool = False
    {% if database != "none" %}
    database_url: str = "{{ database }}+asyncpg://localhost/{{ project_name | to_snake_case }}"
    {% endif %}
    {% if include_auth %}
    jwt_secret_key: str = "dev-secret-key"
    jwt_algorithm: str = "HS256"
    jwt_access_token_expire_minutes: int = 30
    {% endif %}


settings = Settings()
'''

    def _get_fastapi_models_content(self) -> str:
        return '''from datetime import datetime
from enum import Enum
from typing import Optional

from sqlalchemy import String, Integer, DateTime, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class ItemStatus(str, Enum):
    ACTIVE = "active"
    INACTIVE = "inactive"
    PENDING = "pending"


class Item(Base):
    __tablename__ = "items"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(255), nullable=False)
    description: Mapped[Optional[str]] = mapped_column(String(1000), nullable=True)
    status: Mapped[str] = mapped_column(String(20), default=ItemStatus.ACTIVE)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now(), onupdate=func.now())
'''

    def _get_fastapi_schemas_content(self) -> str:
        return '''from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field


class ItemBase(BaseModel):
    name: str = Field(..., min_length=1, max_length=255)
    description: Optional[str] = Field(None, max_length=1000)
    status: str = "active"


class ItemCreate(ItemBase):
    pass


class ItemUpdate(BaseModel):
    name: Optional[str] = Field(None, min_length=1, max_length=255)
    description: Optional[str] = Field(None, max_length=1000)
    status: Optional[str] = None


class ItemResponse(ItemBase):
    id: int
    created_at: datetime
    updated_at: datetime

    class Config:
        from_attributes = True
'''

    def _get_fastapi_router_content(self) -> str:
        return '''from typing import List, Optional

from fastapi import APIRouter, HTTPException, Query

from app.schemas import ItemCreate, ItemResponse, ItemUpdate

router = APIRouter()

items_db = []
next_id = 1


@router.get("", response_model=List[ItemResponse])
async def list_items(
    skip: int = 0,
    limit: int = 100,
    status: Optional[str] = None,
):
    results = items_db[skip:skip + limit]
    if status:
        results = [item for item in results if item.get("status") == status]
    return results


@router.post("", response_model=ItemResponse, status_code=201)
async def create_item(item: ItemCreate):
    global next_id
    new_item = {
        "id": next_id,
        **item.model_dump(),
        "created_at": datetime.utcnow(),
        "updated_at": datetime.utcnow(),
    }
    items_db.append(new_item)
    next_id += 1
    return new_item


@router.get("/{item_id}", response_model=ItemResponse)
async def get_item(item_id: int):
    for item in items_db:
        if item["id"] == item_id:
            return item
    raise HTTPException(status_code=404, detail="Item not found")


@router.put("/{item_id}", response_model=ItemResponse)
async def update_item(item_id: int, item: ItemUpdate):
    for i, db_item in enumerate(items_db):
        if db_item["id"] == item_id:
            update_data = item.model_dump(exclude_unset=True)
            items_db[i].update(update_data)
            items_db[i]["updated_at"] = datetime.utcnow()
            return items_db[i]
    raise HTTPException(status_code=404, detail="Item not found")


@router.delete("/{item_id}", status_code=204)
async def delete_item(item_id: int):
    global items_db
    items_db = [item for item in items_db if item["id"] != item_id]
    return None


from datetime import datetime
'''

    def _get_fastapi_conftest_content(self) -> str:
        return '''import pytest
from fastapi.testclient import TestClient

from main import app


@pytest.fixture
def client():
    return TestClient(app)
'''

    def _get_library_pyproject_content(self) -> str:
        return '''[build-system]
requires = ["setuptools>=68.0", "wheel"]
build-backend = "setuptools.build_meta"

[project]
name = "{{ library_name | to_kebab_case }}"
version = "{{ version }}"
description = "{{ description }}"
readme = "README.md"
requires-python = ">=3.10"
authors = [{ name = "{{ author }}" }]
packages = [{ include = "{{ library_name }}", from = "src" }]

[project.optional-dependencies]
dev = [
    "pytest>=7.4.0",
    "pytest-cov>=4.1.0",
]

[tool.pytest.ini_options]
testpaths = ["tests"]
pythonpath = ["."]
'''

    def _get_library_readme_content(self) -> str:
        return '''# {{ library_name | to_python_class }}

{{ description }}

## Installation

```bash
pip install {{ library_name | to_kebab_case }}
```

## Usage

```python
from {{ library_name }} import Core

core = Core()
result = core.do_something()
```

## Development

```bash
pytest tests/ -v --cov={{ library_name }}
```
'''

    def _get_library_core_content(self) -> str:
        return '''"""Core functionality for {{ library_name }}."""

from typing import Any, Optional


class Core:
    """Main class for {{ library_name }}."""

    def __init__(self, config: Optional[dict[str, Any]] = None) -> None:
        self.config = config or {}

    def do_something(self, value: str) -> str:
        """Process a value and return the result."""
        return f"Processed: {value}"

    def get_version(self) -> str:
        """Return the library version."""
        from . import __version__
        return __version__
'''

    def _get_library_test_content(self) -> str:
        return '''import pytest
from {{ library_name }} import Core


def test_core_initialization():
    core = Core()
    assert core is not None


def test_do_something():
    core = Core()
    result = core.do_something("test")
    assert result == "Processed: test"


def test_get_version():
    core = Core()
    version = core.get_version()
    assert version == "0.1.0"
'''

    def _get_cli_pyproject_content(self) -> str:
        return '''[build-system]
requires = ["setuptools>=68.0", "wheel"]
build-backend = "setuptools.build_meta"

[project]
name = "{{ cli_name | to_kebab_case }}"
version = "0.1.0"
description = "{{ description }}"
readme = "README.md"
requires-python = ">=3.10"
authors = [{ name = "{{ author }}" }]
dependencies = [
    "click>=8.1.7",
    "rich>=13.7.0",
]

[project.scripts]
{{ cli_name }} = "cli:cli"

[project.optional-dependencies]
dev = [
    "pytest>=7.4.0",
]
'''

    def _get_cli_readme_content(self) -> str:
        return '''# {{ cli_name | to_python_class }} CLI

{{ description }}

## Installation

```bash
pip install -e .
```

## Usage

```bash
{{ cli_name }} greet --name World
```

## Commands

- `greet`: Greet someone
'''

    def _get_cli_main_content(self) -> str:
        return '''#!/usr/bin/env python3
"""{{ cli_name }} CLI application."""

import click
from rich.console import Console

from commands import greet

console = Console()


@click.group()
@click.version_option(version="0.1.0")
@click.pass_context
def cli(ctx: click.Context) -> None:
    """{{ cli_name | to_python_class }} - A command line tool."""
    ctx.ensure_object(dict)


cli.add_command(greet)


if __name__ == "__main__":
    cli()
'''

    def _get_cli_command_content(self) -> str:
        return '''import click
from rich.console import Console

console = Console()


@click.command()
@click.option("--name", "-n", default="World", help="Name to greet")
def greet(name: str) -> None:
    """Greet someone."""
    console.print(f"[bold green]Hello, {name}![/bold green]")
'''
