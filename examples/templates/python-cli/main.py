#!/usr/bin/env python3
"""{{ project_name }} - {{ command_name }} command"""

import click

@click.command()
@click.option("--name", default="World", help="Name to greet")
def {{ command_name }}(name):
    click.echo(f"Hello, {name}!")

if __name__ == "__main__":
    {{ command_name }}()
