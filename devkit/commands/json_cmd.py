import json as json_module
import sys
import re
import difflib
from pathlib import Path

import click
import yaml
import toml
from jsondiff import diff as json_diff
from pygments import highlight
from pygments.lexers import JsonLexer, YamlLexer, get_lexer_by_name
from pygments.formatters import TerminalFormatter

try:
    from pygments.lexers import TomlLexer
except ImportError:
    TomlLexer = None

from ..core import Color, cprint


def jq_path(data, path):
    """Evaluate a jq-style path expression against JSON data.
    
    The parser uses a three-stage approach:
    1. **Lexical Analysis**: The regex r'\[(\d+)\]|([^.\[\]]+)' tokenizes the path
       into array indices (captured in group 1) and object keys (captured in group 2).
       This single regex handles both notations simultaneously without needing
       a separate tokenizer pass.
    2. **Syntactic Analysis**: Tokens are processed in sequence, distinguishing
       between array access ([0]) and object access (.key) based on which
       capture group matched.
    3. **Path Evaluation**: Starting from the root data structure, each token
       is applied in sequence. Any access failure (missing key, out-of-bounds index,
       wrong type) returns None gracefully.
    
    Args:
        data: The JSON data structure (dict, list, or scalar) to query.
        path: JQ-style path expression. Supports:
            - "." for root
            - ".key" or "key" for object access
            - "[0]" for array index access
            - "data.users[0].name" for chained access
    
    Returns:
        The value at the specified path, or None if the path does not exist.
    
    Examples:
        >>> jq_path({"data": {"users": [{"name": "alice"}]}}, "data.users[0].name")
        "alice"
        >>> jq_path([1, 2, 3], "[1]")
        2
        >>> jq_path({"a": 1}, "b")
        None
    """
    if not path or path == '.':
        return data
    
    # Strip leading dot to normalize input (handles both ".key" and "key" syntax)
    path = path.lstrip('.')
    
    # Single-pass lexer: matches either [digits] for array index or non-special chars for key
    # Using a single regex with capture groups avoids implementing a full lexer/parser
    # while correctly handling the most common jq path patterns
    parts = re.findall(r'\[(\d+)\]|([^.\[\]]+)', path)
    
    current = data
    for idx, key in parts:
        if idx:
            # Array index access - capture group 1 matched
            try:
                current = current[int(idx)]
            except (IndexError, KeyError, TypeError):
                # Catch all: out-of-bounds, dict with non-int key, or non-subscriptable type
                return None
        else:
            # Object key access - capture group 2 matched
            try:
                current = current[key]
            except (KeyError, TypeError):
                # Key doesn't exist or current is not a dict
                return None
    return current


def load_json(content):
    return json_module.loads(content)


def load_yaml(content):
    return yaml.safe_load(content)


def load_toml(content):
    return toml.loads(content)


def detect_format(content):
    try:
        json_module.loads(content)
        return 'json'
    except json_module.JSONDecodeError:
        pass
    if re.search(r'^\s*\w+\s*=\s*', content, re.MULTILINE):
        try:
            toml.loads(content)
            return 'toml'
        except toml.TomlDecodeError:
            pass
    try:
        yaml.safe_load(content)
        return 'yaml'
    except yaml.YAMLError:
        pass
    try:
        toml.loads(content)
        return 'toml'
    except toml.TomlDecodeError:
        pass
    return None


def colored_output(data, fmt='json', indent=2):
    if fmt == 'json':
        code = json_module.dumps(data, ensure_ascii=False, indent=indent)
        lexer = JsonLexer()
    elif fmt == 'yaml':
        code = yaml.dump(data, allow_unicode=True, default_flow_style=False, sort_keys=False)
        lexer = YamlLexer()
    elif fmt == 'toml':
        code = toml.dumps(data)
        if TomlLexer:
            lexer = TomlLexer()
        else:
            try:
                lexer = get_lexer_by_name('toml')
            except Exception:
                lexer = JsonLexer()
    else:
        return str(data)
    
    return highlight(code, lexer, TerminalFormatter())


def read_input(filepath, content):
    if filepath:
        with open(filepath, 'r', encoding='utf-8') as f:
            return f.read()
    if content:
        return content
    if not sys.stdin.isatty():
        return sys.stdin.read()
    return None


@click.group()
def json():
    """JSON/YAML/TOML processing commands"""
    pass


@json.command()
@click.argument('filepath', required=False, type=click.Path(exists=True))
@click.option('--content', '-c', help='Direct content string to format')
@click.option('--indent', '-i', default=2, show_default=True, help='Indentation spaces')
@click.option('--from', 'from_fmt', type=click.Choice(['json', 'yaml', 'toml']), help='Source format')
@click.option('--to', 'to_fmt', default='json', type=click.Choice(['json', 'yaml', 'toml']), show_default=True, help='Target format')
@click.option('--no-color', is_flag=True, help='Disable colored output')
def format(filepath, content, indent, from_fmt, to_fmt, no_color):
    """Format and convert between JSON/YAML/TOML formats"""
    raw = read_input(filepath, content)
    if not raw:
        cprint('Error: No input provided', Color.RED)
        return
    
    if not from_fmt:
        from_fmt = detect_format(raw)
        if not from_fmt:
            cprint('Error: Could not detect format automatically', Color.RED)
            return
    
    try:
        if from_fmt == 'json':
            data = load_json(raw)
        elif from_fmt == 'yaml':
            data = load_yaml(raw)
        elif from_fmt == 'toml':
            data = load_toml(raw)
    except Exception as e:
        cprint(f'Error parsing {from_fmt}: {e}', Color.RED)
        return
    
    if no_color:
        if to_fmt == 'json':
            click.echo(json_module.dumps(data, ensure_ascii=False, indent=indent))
        elif to_fmt == 'yaml':
            click.echo(yaml.dump(data, allow_unicode=True, default_flow_style=False, sort_keys=False))
        elif to_fmt == 'toml':
            click.echo(toml.dumps(data))
    else:
        click.echo(colored_output(data, to_fmt, indent))


@json.command()
@click.argument('filepath', required=False, type=click.Path(exists=True))
@click.option('--content', '-c', help='Direct content string to minify')
@click.option('--from', 'from_fmt', type=click.Choice(['json', 'yaml', 'toml']), help='Source format')
def minify(filepath, content, from_fmt):
    """Minify JSON (remove whitespace)"""
    raw = read_input(filepath, content)
    if not raw:
        cprint('Error: No input provided', Color.RED)
        return
    
    if not from_fmt:
        from_fmt = detect_format(raw) or 'json'
    
    try:
        if from_fmt == 'json':
            data = load_json(raw)
        elif from_fmt == 'yaml':
            data = load_yaml(raw)
        elif from_fmt == 'toml':
            data = load_toml(raw)
    except Exception as e:
        cprint(f'Error parsing: {e}', Color.RED)
        return
    
    click.echo(json_module.dumps(data, ensure_ascii=False, separators=(',', ':')))


@json.command()
@click.argument('path_expr')
@click.argument('filepath', required=False, type=click.Path(exists=True))
@click.option('--content', '-c', help='Direct content string')
@click.option('--from', 'from_fmt', type=click.Choice(['json', 'yaml', 'toml']), help='Source format')
@click.option('--raw', '-r', is_flag=True, help='Output raw string without quotes for scalar values')
@click.option('--no-color', is_flag=True, help='Disable colored output')
def get(path_expr, filepath, content, from_fmt, raw, no_color):
    """Extract value by jq-style path (e.g., data.users[0].name)"""
    raw_data = read_input(filepath, content)
    if not raw_data:
        cprint('Error: No input provided', Color.RED)
        return
    
    if not from_fmt:
        from_fmt = detect_format(raw_data) or 'json'
    
    try:
        if from_fmt == 'json':
            data = load_json(raw_data)
        elif from_fmt == 'yaml':
            data = load_yaml(raw_data)
        elif from_fmt == 'toml':
            data = load_toml(raw_data)
    except Exception as e:
        cprint(f'Error parsing: {e}', Color.RED)
        return
    
    result = jq_path(data, path_expr)
    if result is None:
        cprint(f'Path not found: {path_expr}', Color.YELLOW)
        return
    
    if raw and isinstance(result, (str, int, float, bool)):
        click.echo(str(result))
    elif no_color:
        if isinstance(result, (dict, list)):
            click.echo(json_module.dumps(result, ensure_ascii=False, indent=2))
        else:
            click.echo(str(result))
    else:
        if isinstance(result, (dict, list)):
            click.echo(colored_output(result, 'json'))
        else:
            cprint(str(result), Color.CYAN)


@json.command()
@click.argument('file1', type=click.Path(exists=True))
@click.argument('file2', type=click.Path(exists=True))
@click.option('--format', '-f', 'diff_format', default='symmetric', 
              type=click.Choice(['symmetric', 'unified', 'context', 'json']),
              show_default=True, help='Diff output format')
@click.option('--no-color', is_flag=True, help='Disable colored output')
def diff(file1, file2, diff_format, no_color):
    """Compare two JSON files and show differences"""
    try:
        with open(file1, 'r', encoding='utf-8') as f:
            data1 = json_module.load(f)
        with open(file2, 'r', encoding='utf-8') as f:
            data2 = json_module.load(f)
    except Exception as e:
        cprint(f'Error reading files: {e}', Color.RED)
        return
    
    if diff_format == 'json':
        delta = json_diff(data1, data2, dump=True)
        if no_color:
            click.echo(delta)
        else:
            click.echo(colored_output(json_module.loads(delta), 'json'))
    elif diff_format in ['unified', 'context']:
        str1 = json_module.dumps(data1, ensure_ascii=False, indent=2).splitlines()
        str2 = json_module.dumps(data2, ensure_ascii=False, indent=2).splitlines()
        
        if diff_format == 'unified':
            lines = difflib.unified_diff(str1, str2, fromfile=file1, tofile=file2, lineterm='')
        else:
            lines = difflib.context_diff(str1, str2, fromfile=file1, tofile=file2, lineterm='')
        
        for line in lines:
            if no_color:
                click.echo(line)
            else:
                if line.startswith('+'):
                    cprint(line, Color.GREEN)
                elif line.startswith('-'):
                    cprint(line, Color.RED)
                elif line.startswith('!'):
                    cprint(line, Color.YELLOW)
                else:
                    click.echo(line)
    else:
        delta = json_diff(data1, data2)
        if not delta:
            cprint('Files are identical', Color.GREEN)
            return
        _print_diff_tree(delta, no_color)


def _print_diff_tree(diff_obj, no_color, indent=0):
    prefix = '  ' * indent
    if isinstance(diff_obj, dict):
        for key, value in diff_obj.items():
            if key == '$insert':
                for k, v in value.items():
                    msg = f'{prefix}+ {k}: {json_module.dumps(v, ensure_ascii=False)}'
                    cprint(msg, Color.GREEN) if not no_color else click.echo(msg)
            elif key == '$delete':
                for k, v in value.items():
                    msg = f'{prefix}- {k}: {json_module.dumps(v, ensure_ascii=False)}'
                    cprint(msg, Color.RED) if not no_color else click.echo(msg)
            elif key == '$update':
                for k, (old, new) in value.items():
                    msg1 = f'{prefix}- {k}: {json_module.dumps(old, ensure_ascii=False)}'
                    msg2 = f'{prefix}+ {k}: {json_module.dumps(new, ensure_ascii=False)}'
                    if not no_color:
                        cprint(msg1, Color.RED)
                        cprint(msg2, Color.GREEN)
                    else:
                        click.echo(msg1)
                        click.echo(msg2)
            else:
                if isinstance(value, dict) and any(k in value for k in ['$insert', '$delete', '$update', '$unchanged']):
                    click.echo(f'{prefix}{key}:')
                    _print_diff_tree(value, no_color, indent + 1)
                else:
                    click.echo(f'{prefix}{key}:')
                    _print_diff_tree(value, no_color, indent + 1)
    elif isinstance(diff_obj, list):
        for i, item in enumerate(diff_obj):
            click.echo(f'{prefix}[{i}]:')
            _print_diff_tree(item, no_color, indent + 1)
    else:
        msg = f'{prefix}{json_module.dumps(diff_obj, ensure_ascii=False)}'
        cprint(msg, Color.YELLOW) if not no_color else click.echo(msg)
