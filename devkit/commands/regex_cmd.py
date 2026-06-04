import re
import sys
from pathlib import Path

import click

from ..core import Color, cprint


REGEX_TEMPLATES = {
    'email': {
        'pattern': r'^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$',
        'description': 'Email address'
    },
    'phone': {
        'pattern': r'^1[3-9]\d{9}$',
        'description': 'Chinese mobile phone number'
    },
    'idcard': {
        'pattern': r'^[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]$',
        'description': 'Chinese ID card number'
    },
    'url': {
        'pattern': r'https?://[^\s/$.?#].[^\s]*$',
        'description': 'URL (http/https)'
    },
    'date': {
        'pattern': r'^\d{4}[-/](0[1-9]|1[0-2])[-/](0[1-9]|[12]\d|3[01])$',
        'description': 'Date (YYYY-MM-DD or YYYY/MM/DD)'
    },
    'ipv4': {
        'pattern': r'^((25[0-5]|2[0-4]\d|[01]?\d\d?)\.){3}(25[0-5]|2[0-4]\d|[01]?\d\d?)$',
        'description': 'IPv4 address'
    },
    'ipv6': {
        'pattern': r'^([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}$',
        'description': 'IPv6 address'
    },
    'chinese': {
        'pattern': r'^[\u4e00-\u9fa5]+$',
        'description': 'Chinese characters only'
    },
    'username': {
        'pattern': r'^[a-zA-Z][a-zA-Z0-9_]{3,15}$',
        'description': 'Username (4-16 chars, start with letter)'
    },
    'password': {
        'pattern': r'^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)[a-zA-Z\d]{8,}$',
        'description': 'Password (min 8 chars, 1 upper, 1 lower, 1 number)'
    }
}


def _highlight_matches(text, pattern, flags=0):
    """Highlight all regex matches in text with yellow background.
    
    **Edge Cases Handled**:
    1. **Zero-length matches**: `re.finditer` returns matches for zero-length assertions
       (e.g., `^`, `$`, `\b`, lookaheads). These are skipped to avoid inserting
       empty highlighted regions which would corrupt the output.
    2. **Overlapping matches**: `re.finditer` returns non-overlapping matches by default,
       but we preserve this behavior rather than forcing overlap.
    3. **Adjacent matches**: `last_end` tracking ensures no characters are duplicated
       or missed when matches are immediately adjacent (match[i].end == match[i+1].start).
    4. **Multi-byte characters**: Python string slicing works on Unicode code points,
       so multi-byte UTF-8 characters are handled correctly as long as `text` is
       a proper str object, not bytes.
    5. **ANSI escape sequences**: If input text already contains ANSI color codes,
       the background highlight may not render correctly due to escape sequence
       interleaving. This is a known limitation.
    
    Args:
        text: The text to search and highlight.
        pattern: The regex pattern string.
        flags: Bitmask of regex flags (re.IGNORECASE, re.MULTILINE, etc.)
    
    Returns:
        Tuple of (highlighted_text, compiled_regex, matches_list) on success,
        or (None, error_message) on invalid regex.
    """
    try:
        regex = re.compile(pattern, flags)
    except re.error as e:
        return None, f'Invalid regex: {e}'
    
    matches = list(regex.finditer(text))
    if not matches:
        return text, None, []
    
    highlighted = ''
    last_end = 0
    for match in matches:
        # Skip zero-length matches to avoid inserting empty highlighted regions.
        # This handles assertions like ^, $, \b, (?=...), (?!...), etc.
        # Without this check, a pattern like '^' would match at position 0 and
        # insert a zero-width highlight that breaks the rendering.
        if match.start() == match.end():
            continue
        
        # Append non-matched text between the end of last match and start of this one
        highlighted += text[last_end:match.start()]
        
        # Wrap the matched text in ANSI color codes for yellow background + black text
        highlighted += Color.wrap(match.group(), Color.BG_YELLOW + Color.BLACK)
        
        last_end = match.end()
    
    # Append any remaining text after the last match
    highlighted += text[last_end:]
    
    return highlighted, regex, matches


@click.group()
def regex():
    """Regular expression testing and debugging"""
    pass


@regex.command('test')
@click.argument('pattern')
@click.argument('text', required=False)
@click.option('--file', '-f', type=click.Path(exists=True), help='Read test text from file')
@click.option('--ignore-case', '-i', is_flag=True, help='Case insensitive matching')
@click.option('--multiline', '-m', is_flag=True, help='Multiline mode')
@click.option('--dotall', '-s', is_flag=True, help='Dot matches all including newline')
@click.option('--unicode', '-u', is_flag=True, help='Unicode matching')
@click.option('--verbose', '-v', is_flag=True, help='Verbose mode (whitespace in pattern ignored)')
@click.option('--groups-only', '-g', is_flag=True, help='Show only capture groups')
def regex_test(pattern, text, file, ignore_case, multiline, dotall, unicode, verbose, groups_only):
    """Test regex pattern against text with highlighted matches"""
    if file:
        with open(file, 'r', encoding='utf-8') as f:
            text = f.read()
    elif not text and not sys.stdin.isatty():
        text = sys.stdin.read()
    elif not text:
        cprint('Error: No test text provided', Color.RED)
        return
    
    flags = 0
    if ignore_case:
        flags |= re.IGNORECASE
    if multiline:
        flags |= re.MULTILINE
    if dotall:
        flags |= re.DOTALL
    if unicode:
        flags |= re.UNICODE
    if verbose:
        flags |= re.VERBOSE
    
    result = _highlight_matches(text, pattern, flags)
    
    if len(result) == 2:
        _, error_msg = result
        cprint(error_msg, Color.RED)
        return
    
    highlighted, regex_obj, matches = result
    
    cprint(f'Pattern: {pattern}', Color.CYAN, bold=True)
    flag_names = []
    if ignore_case: flag_names.append('IGNORECASE')
    if multiline: flag_names.append('MULTILINE')
    if dotall: flag_names.append('DOTALL')
    if verbose: flag_names.append('VERBOSE')
    if flag_names:
        cprint(f'Flags: {", ".join(flag_names)}', Color.CYAN)
    
    if not matches:
        cprint('\nNo matches found', Color.YELLOW)
        return
    
    cprint(f'\nFound {len(matches)} match(es):', Color.GREEN, bold=True)
    
    if not groups_only:
        cprint('\nHighlighted text:', Color.CYAN)
        click.echo(highlighted)
    
    cprint('\nMatch details:', Color.CYAN, bold=True)
    for i, match in enumerate(matches, 1):
        cprint(f'\n  Match {i}:', Color.CYAN)
        cprint(f'    Position: {match.start()}-{match.end()}', Color.CYAN)
        cprint(f'    Full match: ', Color.CYAN, nl=False)
        cprint(match.group(), Color.YELLOW)
        
        if match.groups():
            cprint(f'    Capture groups:', Color.CYAN)
            for j, group in enumerate(match.groups(), 1):
                cprint(f'      {j}: ', Color.MAGENTA, nl=False)
                cprint(group or '(empty)', Color.GREEN)
        
        if match.groupdict():
            cprint(f'    Named groups:', Color.CYAN)
            for name, value in match.groupdict().items():
                cprint(f'      {name}: ', Color.MAGENTA, nl=False)
                cprint(value or '(empty)', Color.GREEN)


@regex.command('replace')
@click.argument('substitution')
@click.argument('text', required=False)
@click.option('--file', '-f', type=click.Path(exists=True), help='Read text from file')
@click.option('--output', '-o', type=click.Path(), help='Output result to file')
@click.option('--ignore-case', '-i', is_flag=True, help='Case insensitive matching')
@click.option('--global', '-g', 'global_replace', is_flag=True, help='Replace all occurrences')
def regex_replace(substitution, text, file, output, ignore_case, global_replace):
    """Sed-style replace: s/pattern/replacement/[flags]
    
    Example: s/foo/bar/g or s/(\\d+)/num:\\\\1/
    """
    if not substitution.startswith('s/'):
        cprint('Error: Substitution must be in s/pattern/replacement/ format', Color.RED)
        return
    
    body = substitution[2:]
    if body.endswith('/'):
        body = body[:-1]
    
    slash_idx = body.find('/')
    if slash_idx == -1:
        cprint('Error: Invalid substitution format. Use s/pattern/replacement/', Color.RED)
        return
    
    pattern = body[:slash_idx]
    replacement = body[slash_idx + 1:]
    
    if file:
        with open(file, 'r', encoding='utf-8') as f:
            text = f.read()
    elif not text and not sys.stdin.isatty():
        text = sys.stdin.read()
    elif not text:
        cprint('Error: No text provided', Color.RED)
        return
    
    flags = 0
    count = 1
    
    if ignore_case:
        flags |= re.IGNORECASE
    
    if global_replace:
        count = 0
    
    try:
        result = re.sub(pattern, replacement, text, count=count, flags=flags)
    except re.error as e:
        cprint(f'Invalid regex: {e}', Color.RED)
        return
    
    if output:
        with open(output, 'w', encoding='utf-8') as f:
            f.write(result)
        cprint(f'Result saved to {output}', Color.GREEN)
    else:
        cprint('Before:', Color.CYAN, bold=True)
        click.echo(text)
        cprint('\nAfter:', Color.GREEN, bold=True)
        click.echo(result)


@regex.command('templates')
@click.argument('name', required=False)
@click.option('--search', '-s', help='Search templates by name or description')
def regex_templates(name, search):
    """List common regex templates or show details"""
    if name:
        if name in REGEX_TEMPLATES:
            template = REGEX_TEMPLATES[name]
            cprint(f'Template: {name}', Color.CYAN, bold=True)
            cprint(f'Description: {template["description"]}', Color.CYAN)
            cprint(f'Pattern: {template["pattern"]}', Color.GREEN)
        else:
            cprint(f'Template not found: {name}', Color.RED)
        return
    
    templates = REGEX_TEMPLATES
    if search:
        search_lower = search.lower()
        templates = {
            k: v for k, v in REGEX_TEMPLATES.items()
            if search_lower in k.lower() or search_lower in v['description'].lower()
        }
    
    if not templates:
        cprint('No templates found', Color.YELLOW)
        return
    
    cprint('Available regex templates:', Color.CYAN, bold=True)
    for name, template in templates.items():
        cprint(f'  {name:12} ', Color.MAGENTA, nl=False)
        cprint(template['description'], Color.GREEN)
        cprint(f'              {template["pattern"]}', Color.CYAN)


@regex.command('validate')
@click.argument('pattern')
@click.option('--test', '-t', help='Test string to validate')
@click.option('--template', help='Use a predefined template name')
def regex_validate(pattern, test, template):
    """Validate a regex pattern or test if a string matches"""
    if template:
        if template in REGEX_TEMPLATES:
            pattern = REGEX_TEMPLATES[template]['pattern']
            cprint(f'Using template: {template} - {REGEX_TEMPLATES[template]["description"]}', Color.CYAN)
        else:
            cprint(f'Template not found: {template}', Color.RED)
            return
    
    try:
        regex = re.compile(pattern)
        cprint(f'Regex is valid: {pattern}', Color.GREEN)
    except re.error as e:
        cprint(f'Invalid regex: {e}', Color.RED)
        return
    
    if test:
        if regex.fullmatch(test):
            cprint(f'Test string matches: {test}', Color.GREEN)
        else:
            cprint(f'Test string does not match: {test}', Color.RED)
            
            partial_match = regex.search(test)
            if partial_match:
                cprint(f'Note: Partial match found at position {partial_match.start()}', Color.YELLOW)
                cprint(f'Matched: {partial_match.group()}', Color.YELLOW)


@regex.command('info')
@click.argument('pattern')
def regex_info(pattern):
    """Show detailed information about a regex pattern"""
    try:
        regex = re.compile(pattern)
    except re.error as e:
        cprint(f'Invalid regex: {e}', Color.RED)
        return
    
    cprint(f'Pattern: {pattern}', Color.CYAN, bold=True)
    cprint(f'Flags: {regex.flags}', Color.CYAN)
    cprint(f'Groups: {regex.groups}', Color.CYAN)
    
    if regex.groupindex:
        cprint(f'Named groups:', Color.CYAN)
        for name, index in regex.groupindex.items():
            cprint(f'  {name}: group {index}', Color.MAGENTA)
    
    cprint(f'\nPattern analysis:', Color.CYAN, bold=True)
    
    patterns = [
        (r'\^', 'Anchors to start of string'),
        (r'\$', 'Anchors to end of string'),
        (r'\.', 'Matches any character (except newline)'),
        (r'\*', 'Matches 0 or more times'),
        (r'\+', 'Matches 1 or more times'),
        (r'\?', 'Matches 0 or 1 time'),
        (r'\{.*?\}', 'Specific repetition'),
        (r'\[.*?\]', 'Character class'),
        (r'\(.*?\)', 'Group or capture'),
        (r'\|', 'Alternation (OR)'),
        (r'\\d', 'Matches digit'),
        (r'\\D', 'Matches non-digit'),
        (r'\\w', 'Matches word character'),
        (r'\\W', 'Matches non-word character'),
        (r'\\s', 'Matches whitespace'),
        (r'\\S', 'Matches non-whitespace'),
        (r'\\b', 'Word boundary'),
        (r'\\B', 'Non-word boundary'),
        (r'\(\?\:', 'Non-capturing group'),
        (r'\(\?=', 'Positive lookahead'),
        (r'\(\?!', 'Negative lookahead'),
        (r'\(\?<=', 'Positive lookbehind'),
        (r'\(\?<!', 'Negative lookbehind'),
    ]
    
    for pat, desc in patterns:
        if re.search(pat, pattern):
            cprint(f'  ✓ {desc}', Color.GREEN)
