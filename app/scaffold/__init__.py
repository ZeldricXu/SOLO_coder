"""
项目脚手架生成模块 - 基于模板生成项目骨架
"""
from .generator import (
    ProjectGenerator, TemplateEngine,
    TemplateVariable, ProjectTemplate,
    generate_project, interactive_generate
)

__all__ = [
    "ProjectGenerator", "TemplateEngine",
    "TemplateVariable", "ProjectTemplate",
    "generate_project", "interactive_generate"
]
