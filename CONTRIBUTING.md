# Contributing to DevKit CLI

First off, thanks for taking the time to contribute! 🎉

The following is a set of guidelines for contributing to DevKit CLI. These are mostly guidelines, not rules. Use your best judgment, and feel free to propose changes to this document in a pull request.

## Table of Contents

- [Code of Conduct](#code-of-conduct)
- [Project Structure](#project-structure)
- [Architecture Overview](#architecture-overview)
- [How Can I Contribute?](#how-can-i-contribute)
  - [Reporting Bugs](#reporting-bugs)
  - [Suggesting Enhancements](#suggesting-enhancements)
  - [Good First Issues](#good-first-issues)
- [Development Workflow](#development-workflow)
  - [Prerequisites](#prerequisites)
  - [Setting Up the Environment](#setting-up-the-environment)
- [Adding a New Category](#adding-a-new-category)
- [Coding Standards](#coding-standards)
  - [Ruff Rules](#ruff-rules)
  - [Type Annotations](#type-annotations)
  - [Docstrings](#docstrings)
  - [Git Commit Messages](#git-commit-messages)
- [Pull Request Process](#pull-request-process)
- [Testing](#testing)

## Code of Conduct

This project and everyone participating in it is governed by our Code of Conduct. By participating, you are expected to uphold this code.

## Project Structure

```
devkit/
├── __init__.py              # Package version
├── cli.py                   # Command registration and parameter parsing ONLY
├── commands/                # One directory per category
│   ├── __init__.py
│   ├── json_cmd.py          # JSON/YAML/TOML processing
│   ├── crypto.py            # Encryption and certificate tools
│   ├── net.py               # Network diagnostics
│   ├── regex_cmd.py         # Regex debugger
│   ├── codec.py             # Encoding/decoding tools
│   ├── file_cmd.py          # File batch operations
│   ├── git_cmd.py           # Git helpers
│   ├── time_cmd.py          # Time/date utilities
│   ├── codegen.py           # Code generation
│   ├── db.py                # Database tools
│   ├── api.py               # API testing tools
│   └── sysmon.py            # System monitoring
└── core/                    # Shared public library
    ├── __init__.py
    ├── color.py             # Color constants and cprint()
    ├── config.py            # Configuration management
    └── http_client.py       # HTTP client wrapper
tests/
├── conftest.py
├── test_json_cmd.py
├── test_crypto.py
...
```

### File Naming Convention
- Category command files: `<category>_cmd.py` (e.g., `json_cmd.py`, `file_cmd.py`)
- For single-word categories: `<category>.py` (e.g., `crypto.py`, `net.py`, `db.py`)

## Architecture Overview

### Dependency Graph
```
                    ┌──────────────┐
                    │   cli.py     │
                    │ (command     │
                    │  registration)│
                    └──────┬───────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
┌───────▼──────┐   ┌───────▼──────┐   ┌───────▼──────┐
│ commands/    │   │ commands/    │   │ commands/    │
│ json_cmd.py  │   │ crypto.py    │   │ net.py       │
└──────────────┘   └──────────────┘   └──────────────┘
        │                  │                  │
        └──────────────────┼──────────────────┘
                           │
                    ┌──────▼───────┐
                    │   core/      │
                    │ color.py     │
                    │ config.py    │
                    │ http_client.py│
                    └──────────────┘
```

### Key Principles
1. **core is shared by all categories** - every category depends on core
2. **Categories are independent** - no category imports from another category
3. **cli.py is thin** - only does command registration and parameter parsing, NO business logic
4. **Business logic stays in commands/** - each command file contains its own logic
5. **Functions in core must be used by ≥2 categories** - no "fake shared" functions

## How Can I Contribute?

### Reporting Bugs

- **Use the issue template** for bug reports
- **Include detailed steps** to reproduce the issue
- **Mention your environment** (OS, Python version, devkit version)
- **Include command output** with `--no-color` flag if possible

### Suggesting Enhancements

- **Check existing issues** first to avoid duplicates
- **Use the feature request template**
- **Explain the use case** clearly - what problem does this solve?
- **Consider scope** - does this belong in devkit or a separate tool?

### Good First Issues

Issues labeled with `good first issue` are perfect for new contributors:

- 🔤 **Add new hash algorithm support** (e.g., BLAKE2, SHA-3)
- 💡 **Improve error messages** for a command
- 📝 **Add more examples** to a command's help text
- 🎨 **Add a new color theme** option
- 📊 **Add a new output format** (e.g., XML, Markdown table)
- 🔧 **Add validation** for a command's arguments
- 📚 **Improve docstrings** for complex functions
- 🧪 **Add more test cases** for edge cases

## Development Workflow

### Prerequisites
- Python 3.8 or higher
- pip
- (Optional) keyring for secure credential storage

### Setting Up the Environment

1. **Fork the repository** and clone your fork:
   ```bash
   git clone https://github.com/<your-username>/devkit.git
   cd devkit
   ```

2. **Create a virtual environment**:
   ```bash
   python -m venv .venv
   source .venv/bin/activate  # On Windows: .venv\Scripts\activate
   ```

3. **Install in development mode** with all dependencies:
   ```bash
   pip install -e ".[all,dev]"
   ```

4. **Verify installation**:
   ```bash
   devkit --version
   ```

5. **Run the test suite**:
   ```bash
   pytest tests/ -v
   ```

## Adding a New Category

Want to add a new category (e.g., `devkit docker`)? Follow these steps:

### Step 1: Create the command file

Create `devkit/commands/<name>.py`:

```python
import click
from ..core import Color, cprint

# Pure business logic functions first
def do_something(param1, param2):
    """Core logic for the feature.
    
    Args:
        param1: Description of param1
        param2: Description of param2
    
    Returns:
        Description of return value
    
    Raises:
        ValueError: If param1 is invalid
    """
    # Implementation here
    return result

# Then Click command wrappers
@click.group()
def mycategory():
    """Description of what this category does"""
    pass

@mycategory.command('action')
@click.argument('input')
@click.option('--flag', '-f', is_flag=True, help='Enable special mode')
def mycategory_action(input, flag):
    """Description of what this action does
    
    Examples:
      devkit mycategory action somefile.txt
      devkit mycategory action input.json --flag
    """
    try:
        result = do_something(input, flag)
        cprint(f'Success: {result}', Color.GREEN)
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)
```

### Step 2: Register in cli.py

Edit `devkit/cli.py`:

```python
from .commands.mycategory import mycategory

# ... at the bottom, after other registrations:
cli.add_command(mycategory)
```

### Step 3: Write tests

Create `tests/test_mycategory.py`:

```python
import pytest
from click.testing import CliRunner
from devkit.cli import cli

@pytest.fixture
def runner():
    return CliRunner()

class TestMyCategory:
    def test_basic_functionality(self, runner):
        result = runner.invoke(cli, ['mycategory', 'action', 'test'])
        assert result.exit_code == 0
        assert 'expected output' in result.output
    
    def test_error_handling(self, runner):
        result = runner.invoke(cli, ['mycategory', 'action', 'invalid'])
        assert result.exit_code == 1
        assert 'Error' in result.output
```

### Step 4: Update documentation

1. Add the category to `README.md` command list
2. Add usage examples to `README.md`
3. Add the category to the CLI help text in `cli.py`

## Coding Standards

### Ruff Rules

We use `ruff` for linting with the following configuration (in `pyproject.toml`):

```toml
[tool.ruff]
line-length = 100
target-version = "py38"
select = ["E", "F", "W", "I", "N", "UP"]
ignore = ["E501", "W291", "W293"]
```

Run linting:
```bash
ruff check devkit/
ruff format devkit/  # Auto-fix formatting
```

### Type Annotations

**All public functions must have type annotations**:

```python
# ✅ Good
def process_data(data: dict[str, Any], options: dict[str, bool]) -> list[str]:
    ...

# ❌ Bad
def process_data(data, options):
    ...
```

Use `mypy` for type checking:
```bash
mypy devkit/
```

### Docstrings

Use **Google-style docstrings** for all public functions:

```python
def parse_jq_path(path: str) -> list[tuple[str, str]]:
    """Parse a jq-style path expression into tokens.
    
    Args:
        path: JQ path expression (e.g., "data.users[0].name")
    
    Returns:
        List of (token_type, token_value) tuples.
        Token types: "key", "index"
    
    Raises:
        ValueError: If the path contains invalid syntax
    
    Examples:
        >>> parse_jq_path("data.users[0].name")
        [("key", "data"), ("key", "users"), ("index", "0"), ("key", "name")]
    """
```

### Git Commit Messages

Follow the [Conventional Commits](https://www.conventionalcommits.org/) specification:

```
feat: add new hash algorithm BLAKE2
fix: handle empty JSON arrays in jq parser
docs: update installation instructions
test: add edge cases for regex module
refactor: simplify AES-GCM authentication logic
perf: improve JSON formatting speed for large files
```

## Pull Request Process

1. **Open an issue first** for major changes to discuss the design
2. **Fork the repo** and create a feature branch from `main`:
   ```bash
   git checkout -b feature/my-new-feature
   ```
3. **Make your changes** following the coding standards
4. **Add tests** - PRs without tests will not be merged
5. **Ensure CI passes**:
   ```bash
   pytest tests/ -v
   ruff check devkit/
   mypy devkit/
   ```
6. **Submit the PR** with a clear title and description
7. **Wait for CI to go green** - all checks must pass
8. **Address review feedback** - be responsive to maintainer comments
9. **Squash commits** if requested before merging

### PR Checklist
- [ ] I have opened an issue for this change (for major features)
- [ ] I have added tests that prove my fix is effective or that my feature works
- [ ] I have added necessary documentation (if appropriate)
- [ ] My code follows the ruff linting rules
- [ ] My code has type annotations where required
- [ ] All new and existing tests pass locally

## Testing

### Running Tests

```bash
# Run all tests
pytest tests/ -v

# Run tests for a specific module
pytest tests/test_json_cmd.py -v

# Run with coverage
pytest tests/ -v --cov=devkit --cov-report=html

# Run on multiple Python versions (requires pyenv)
tox
```

### Test Coverage Goal

We aim for **≥80% test coverage** for all modules.

### Test Best Practices

1. **Test both success and failure cases**
2. **Test edge cases** (empty input, maximum values, special characters)
3. **Use fixtures** for common test setup
4. **Mock external dependencies** (HTTP requests, database connections)
5. **Don't test implementation details** - test behavior

---

Thank you for contributing to DevKit CLI! 🙌
