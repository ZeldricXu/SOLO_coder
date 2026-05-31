from __future__ import annotations

import logging
import os
import sys
from typing import Optional

import click
from rich.console import Console
from rich.panel import Panel
from rich.table import Table

from src.scaffold.generator import ProjectGenerator
from src.scaffold.models import ProjectConfig
from src.scaffold.prompts import InteractivePrompter

console = Console()
logger = logging.getLogger(__name__)


@click.group()
@click.version_option(version="0.1.0")
def cli() -> None:
    """Project Scaffold Generator - Generate project templates quickly."""
    pass


@cli.command()
@click.option("--template", "-t", help="Template ID to use")
@click.option("--name", "-n", help="Project name")
@click.option("--output", "-o", help="Output directory", default=".")
@click.option("--author", "-a", help="Author name")
@click.option("--email", "-e", help="Author email")
@click.option("--description", "-d", help="Project description", default="")
@click.option("--interactive/--no-interactive", default=True, help="Enable interactive mode")
@click.option("--overwrite/--no-overwrite", default=False, help="Overwrite existing files")
def create(
    template: Optional[str],
    name: Optional[str],
    output: str,
    author: Optional[str],
    email: Optional[str],
    description: str,
    interactive: bool,
    overwrite: bool,
) -> None:
    """Create a new project from a template."""
    generator = ProjectGenerator()

    if not template:
        templates = generator.template_repo.list()
        table = Table(title="Available Templates")
        table.add_column("ID", style="cyan")
        table.add_column("Name", style="green")
        table.add_column("Description")
        table.add_column("Type", style="yellow")
        for t in templates:
            table.add_row(t.template_id, t.name, t.description, t.project_type.value)
        console.print(table)
        template = click.prompt("Select template ID", type=str)

    template_def = generator.template_repo.get(template)
    if not template_def:
        console.print(f"[red]Template '{template}' not found[/red]")
        sys.exit(1)

    console.print(Panel.fit(
        f"[bold]Generating project using template:[/bold] {template_def.name}",
        title="Project Generator",
        border_style="green",
    ))

    variables: dict[str, str] = {}
    if interactive:
        prompter = InteractivePrompter()
        if not name:
            name = prompter.prompt("Project name", required=True)
        if not author:
            author = prompter.prompt("Author name", required=True)
        if not email:
            email = prompter.prompt("Author email", required=True)
        if not description:
            description = prompter.prompt("Project description", default="")
        variables = prompter.collect_variables(template_def.variables)

    config = ProjectConfig(
        name=name or "my-project",
        description=description,
        template_id=template,
        author=author or "Anonymous",
        email=email or "anonymous@example.com",
        variables=variables,
        output_dir=os.path.join(output, name or "my-project"),
        overwrite=overwrite,
    )

    with console.status("[bold green]Generating project files...[/bold green]"):
        result = generator.generate(config)

    if result.success:
        console.print(f"\n[bold green]✓ Project generated successfully![/bold green]")
        console.print(f"  Output directory: {result.output_dir}")
        console.print(f"  Files generated: {result.files_generated}")
        if result.files_skipped > 0:
            console.print(f"  Files skipped: {result.files_skipped}")
        if result.warnings:
            console.print("\n[yellow]Warnings:[/yellow]")
            for warning in result.warnings:
                console.print(f"  • {warning}")
        console.print(f"\n[bold]Next steps:[/bold]")
        console.print(f"  cd {result.output_dir}")
        console.print(f"  pip install -r requirements.txt")
        console.print(f"  python main.py")
    else:
        console.print(f"\n[bold red]✗ Project generation failed[/bold red]")
        console.print(f"  Files generated: {result.files_generated}")
        console.print(f"  Errors: {len(result.errors)}")
        for error in result.errors:
            console.print(f"  • [red]{error}[/red]")
        sys.exit(1)


@cli.command("list")
def list_templates() -> None:
    """List available templates."""
    generator = ProjectGenerator()
    templates = generator.template_repo.list()

    table = Table(title="Available Templates")
    table.add_column("ID", style="cyan")
    table.add_column("Name", style="green")
    table.add_column("Description")
    table.add_column("Type", style="yellow")
    table.add_column("Variables", style="magenta")

    for t in templates:
        var_names = ", ".join(v.name for v in t.variables)
        table.add_row(t.template_id, t.name, t.description, t.project_type.value, var_names)

    console.print(table)


@cli.command()
@click.argument("template_id")
def show(template_id: str) -> None:
    """Show details of a specific template."""
    generator = ProjectGenerator()
    template = generator.template_repo.get(template_id)

    if not template:
        console.print(f"[red]Template '{template_id}' not found[/red]")
        sys.exit(1)

    console.print(Panel.fit(
        f"[bold]{template.name}[/bold]\n"
        f"{template.description}\n\n"
        f"[bold]Version:[/bold] {template.version}\n"
        f"[bold]Type:[/bold] {template.project_type.value}\n"
        f"[bold]Files:[/bold] {len(template.files)}\n"
        f"[bold]Dependencies:[/bold] {len(template.dependencies)}",
        title=f"Template: {template_id}",
        border_style="cyan",
    ))

    if template.variables:
        var_table = Table(title="Variables")
        var_table.add_column("Name", style="cyan")
        var_table.add_column("Type", style="yellow")
        var_table.add_column("Required", style="red")
        var_table.add_column("Default", style="green")
        var_table.add_column("Description")
        for v in template.variables:
            var_table.add_row(
                v.name,
                v.type,
                "✓" if v.required else "✗",
                str(v.default) if v.default is not None else "-",
                v.description,
            )
        console.print(var_table)


def main() -> None:
    cli()


if __name__ == "__main__":
    main()
