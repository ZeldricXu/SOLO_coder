#!/usr/bin/env python3
"""greeting-cli - greet command"""

import click

@click.command()
@click.option("--name", default="World", help="Name to greet")
def greet(name):
    click.echo(f"Hello, {name}!")

if __name__ == "__main__":
    greet()
