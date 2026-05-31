"""
示例2: 项目脚手架生成模块
展示如何使用 InMemoryFileSystem 进行单元测试
"""

import asyncio
import sys
import os

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from src.core import ScaffoldConfig
from src.infrastructure.template import (
    Jinja2TemplateEngine,
    InMemoryFileSystem,
    FileSystemAdapter,
)
from src.modules.scaffold import (
    ProjectScaffold,
    TemplateRegistry,
)


async def test_scaffold_with_memory_fs():
    """使用内存文件系统测试脚手架 - 无需真实文件系统"""
    print("=== 项目脚手架示例 (内存文件系统) ===\n")

    mem_fs = InMemoryFileSystem()
    template_engine = Jinja2TemplateEngine()

    mem_fs.create_dir("templates/python-service")
    mem_fs.write_file(
        "templates/python-service/template.json",
        """{
    "name": "Python Microservice",
    "description": "A Python microservice template",
    "project_type": "service",
    "language": "python",
    "parameters": [
        {"name": "use_docker", "description": "Use Docker?", "type": "boolean", "default": true},
        {"name": "db_type", "description": "Database type", "choices": ["postgresql", "mysql", "sqlite"], "default": "postgresql"}
    ]
}"""
    )

    mem_fs.write_file(
        "templates/python-service/README.md",
        "# {{ project_name }}\n\nAuthor: {{ author }}\n\nDB: {{ db_type }}"
    )
    mem_fs.write_file(
        "templates/python-service/src/main.py",
        "from fastapi import FastAPI\n\napp = FastAPI(title=\"{{ project_name }}\")\n\n@app.get(\"/\")\ndef read_root():\n    return {\"message\": \"Hello from {{ project_name }}\"}"
    )
    mem_fs.write_file(
        "templates/python-service/requirements.txt",
        "fastapi>=0.100.0\nuvicorn>=0.23.0\n{% if use_docker %}docker>=6.0.0\n{% endif %}"
    )

    registry = TemplateRegistry("templates", mem_fs)

    print("可用模板:")
    for t in registry.list_templates():
        print(f"  - {t.name}: {t.description} ({t.language})")

    scaffold = ProjectScaffold(
        template_engine=template_engine,
        file_system=mem_fs,
        template_registry=registry,
    )

    config = ScaffoldConfig(
        project_name="user-service",
        project_type="service",
        language="python",
        author="Alice Chen",
        template="python-service",
        output_dir="output/user-service",
        parameters={"use_docker": True, "db_type": "postgresql"},
    )

    print(f"\n生成项目: {config.project_name}")
    result = await scaffold.generate(config)

    print(f"\n生成结果: {'成功' if result.success else '失败'}")
    print(f"创建文件数: {len(result.created_files)}")

    print("\n生成的文件内容:")
    for path, content in mem_fs.get_all_files().items():
        if path.startswith("output/"):
            print(f"\n--- {path} ---")
            print(content)


async def test_scaffold_with_real_fs():
    """使用真实文件系统生成项目"""
    print("\n=== 项目脚手架示例 (真实文件系统) ===\n")

    template_dir = "examples/templates"
    output_dir = "examples/output"

    os.makedirs(os.path.join(template_dir, "python-cli"), exist_ok=True)

    with open(os.path.join(template_dir, "python-cli", "template.json"), "w") as f:
        f.write("""{
    "name": "Python CLI Tool",
    "description": "A Python command-line tool",
    "project_type": "cli",
    "language": "python",
    "parameters": [
        {"name": "command_name", "description": "CLI command name", "default": "mycmd"}
    ]
}""")

    with open(os.path.join(template_dir, "python-cli", "main.py"), "w") as f:
        f.write('''#!/usr/bin/env python3
"""{{ project_name }} - {{ command_name }} command"""

import click

@click.command()
@click.option("--name", default="World", help="Name to greet")
def {{ command_name }}(name):
    click.echo(f"Hello, {name}!")

if __name__ == "__main__":
    {{ command_name }}()
''')

    real_fs = FileSystemAdapter()
    template_engine = Jinja2TemplateEngine()
    registry = TemplateRegistry(template_dir, real_fs)
    scaffold = ProjectScaffold(template_engine, real_fs, registry)

    config = ScaffoldConfig(
        project_name="greeting-cli",
        project_type="cli",
        language="python",
        author="Bob",
        template="python-cli",
        output_dir=os.path.join(output_dir, "greeting-cli"),
        parameters={"command_name": "greet"},
    )

    result = await scaffold.generate(config)
    print(f"生成结果: {'成功' if result.success else '失败'}")
    print(f"输出目录: {config.output_dir}")

    for f in result.created_files:
        print(f"  - {f}")


if __name__ == "__main__":
    asyncio.run(test_scaffold_with_memory_fs())
    asyncio.run(test_scaffold_with_real_fs())
