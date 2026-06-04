import csv
import json
import os
import re
import shutil
import sys
from pathlib import Path

import click
import chardet

from ..core import Color, cprint


def _detect_encoding(filepath):
    with open(filepath, 'rb') as f:
        raw_data = f.read()
    result = chardet.detect(raw_data)
    return result['encoding'], result['confidence']


@click.group()
def file():
    """File batch processing commands"""
    pass


@file.command('rename')
@click.argument('directory', type=click.Path(exists=True, file_okay=False))
@click.option('--pattern', '-p', required=True, help='Regex pattern to match filenames')
@click.option('--replace', '-r', required=True, help='Replacement pattern (supports {i} for numbering, e.g., file_{:03d})')
@click.option('--start', '-s', default=1, show_default=True, help='Starting number for {i} template')
@click.option('--step', default=1, show_default=True, help='Step for numbering')
@click.option('--include-ext', '-e', is_flag=True, help='Match pattern against full name including extension')
@click.option('--recursive', '-R', is_flag=True, help='Recurse into subdirectories')
@click.option('--dry-run', '-n', is_flag=True, help='Show what would be renamed without actually renaming')
@click.option('--verbose', '-v', is_flag=True, help='Show verbose output')
def file_rename(directory, pattern, replace, start, step, include_ext, recursive, dry_run, verbose):
    """Batch rename files with regex and numbering templates
    
    Examples:
      devkit file rename ./docs -p 'doc(\\d+)' -r 'document_{\\1}'
      devkit file rename ./imgs -p '.*' -r 'photo_{:03d}.jpg' --start 1
    """
    try:
        regex = re.compile(pattern)
    except re.error as e:
        cprint(f'Invalid regex pattern: {e}', Color.RED)
        return
    
    path = Path(directory)
    files = []
    
    if recursive:
        for root, dirs, filenames in os.walk(path):
            for filename in filenames:
                files.append(Path(root) / filename)
    else:
        files = [f for f in path.iterdir() if f.is_file()]
    
    files.sort()
    renamed_count = 0
    counter = start
    
    for filepath in files:
        name_to_match = filepath.name if include_ext else filepath.stem
        match = regex.search(name_to_match)
        
        if match:
            new_name = replace
            groups = match.groups()
            
            for i, group in enumerate(groups, 1):
                new_name = new_name.replace(f'\\{i}', group or '')
            
            if '{i' in new_name:
                new_name = new_name.replace('{i}', str(counter))
                for fmt in re.findall(r'\{i:([^}]+)\}', new_name):
                    new_name = new_name.replace(f'{{i:{fmt}}}', format(counter, fmt))
            
            if include_ext:
                new_filename = new_name
            else:
                ext = filepath.suffix
                new_filename = new_name + ext
            
            new_filepath = filepath.parent / new_filename
            
            if filepath == new_filepath:
                continue
            
            if verbose or dry_run:
                action = 'Would rename' if dry_run else 'Renaming'
                cprint(f'{action}: ', Color.CYAN, nl=False)
                click.echo(f'{filepath.name} -> {new_filename}')
            
            if not dry_run:
                if new_filepath.exists():
                    cprint(f'Skipping: {new_filename} already exists', Color.YELLOW)
                    continue
                filepath.rename(new_filepath)
            
            renamed_count += 1
            counter += step
    
    cprint(f'\n{f"Would rename" if dry_run else "Renamed"} {renamed_count} file(s)', Color.GREEN, bold=True)


@file.command('replace')
@click.argument('directory', type=click.Path(exists=True, file_okay=False))
@click.option('--pattern', '-p', required=True, help='Regex pattern to search')
@click.option('--replace', '-r', required=True, help='Replacement string')
@click.option('--glob', '-g', default='*', show_default=True, help='File glob pattern')
@click.option('--recursive', '-R', is_flag=True, help='Recurse into subdirectories')
@click.option('--ignore-case', '-i', is_flag=True, help='Case insensitive search')
@click.option('--encoding', '-e', help='File encoding (default: auto-detect)')
@click.option('--dry-run', '-n', is_flag=True, help='Show what would be changed')
@click.option('--verbose', '-v', is_flag=True, help='Show verbose output')
def file_replace(directory, pattern, replace, glob, recursive, ignore_case, encoding, dry_run, verbose):
    """Recursive search and replace in file contents"""
    flags = re.IGNORECASE if ignore_case else 0
    try:
        regex = re.compile(pattern, flags)
    except re.error as e:
        cprint(f'Invalid regex pattern: {e}', Color.RED)
        return
    
    path = Path(directory)
    files = []
    
    if recursive:
        files = list(path.rglob(glob))
    else:
        files = list(path.glob(glob))
    
    files = [f for f in files if f.is_file()]
    
    changed_files = 0
    total_changes = 0
    
    for filepath in files:
        try:
            if not encoding:
                file_encoding, confidence = _detect_encoding(filepath)
                if confidence < 0.5:
                    file_encoding = 'utf-8'
            else:
                file_encoding = encoding
            
            with open(filepath, 'r', encoding=file_encoding) as f:
                content = f.read()
        except (UnicodeDecodeError, PermissionError, IsADirectoryError):
            continue
        
        matches = list(regex.finditer(content))
        if not matches:
            continue
        
        new_content = regex.sub(replace, content)
        
        if verbose or dry_run:
            action = 'Would modify' if dry_run else 'Modifying'
            cprint(f'{action} {filepath}: {len(matches)} match(es)', Color.CYAN)
            for match in matches[:5]:
                cprint(f'  Line {content[:match.start()].count(chr(10)) + 1}: ', Color.YELLOW, nl=False)
                snippet_start = max(0, match.start() - 20)
                snippet_end = min(len(content), match.end() + 20)
                click.echo(f'...{content[snippet_start:match.start()]}{Color.wrap(match.group(), Color.BG_YELLOW + Color.BLACK)}{content[match.end():snippet_end]}...')
        
        if not dry_run:
            try:
                with open(filepath, 'w', encoding=file_encoding) as f:
                    f.write(new_content)
            except Exception as e:
                cprint(f'Error writing {filepath}: {e}', Color.RED)
                continue
        
        changed_files += 1
        total_changes += len(matches)
    
    cprint(f'\n{f"Would modify" if dry_run else "Modified"} {changed_files} file(s), {total_changes} change(s)', Color.GREEN, bold=True)


@file.command('split')
@click.argument('filepath', type=click.Path(exists=True))
@click.option('--size', '-s', default='1M', help='Chunk size (e.g., 100K, 1M, 1G)')
@click.option('--prefix', '-p', help='Output file prefix (default: input filename)')
@click.option('--output-dir', '-o', type=click.Path(file_okay=False), help='Output directory')
@click.option('--verbose', '-v', is_flag=True, help='Show verbose output')
def file_split(filepath, size, prefix, output_dir, verbose):
    """Split a large file into smaller chunks"""
    size_map = {'K': 1024, 'M': 1024 * 1024, 'G': 1024 * 1024 * 1024}
    try:
        if size[-1].upper() in size_map:
            chunk_size = int(size[:-1]) * size_map[size[-1].upper()]
        else:
            chunk_size = int(size)
    except ValueError:
        cprint(f'Invalid size: {size}', Color.RED)
        return
    
    input_path = Path(filepath)
    if not prefix:
        prefix = input_path.name + '_'
    
    out_dir = Path(output_dir) if output_dir else input_path.parent
    out_dir.mkdir(parents=True, exist_ok=True)
    
    file_size = input_path.stat().st_size
    total_chunks = (file_size + chunk_size - 1) // chunk_size
    
    cprint(f'Splitting {input_path.name} ({file_size} bytes) into {total_chunks} chunks of {chunk_size} bytes', Color.CYAN, bold=True)
    
    chunk_num = 0
    with open(input_path, 'rb') as f:
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            
            chunk_path = out_dir / f'{prefix}{chunk_num:04d}'
            with open(chunk_path, 'wb') as out:
                out.write(chunk)
            
            if verbose:
                cprint(f'Created: {chunk_path.name} ({len(chunk)} bytes)', Color.GREEN)
            
            chunk_num += 1
    
    cprint(f'\nSplit complete. {chunk_num} chunks created.', Color.GREEN, bold=True)


@file.command('merge')
@click.argument('prefix')
@click.argument('output', type=click.Path())
@click.option('--input-dir', '-i', type=click.Path(exists=True, file_okay=False), help='Input directory')
@click.option('--verbose', '-v', is_flag=True, help='Show verbose output')
def file_merge(prefix, output, input_dir, verbose):
    """Merge previously split chunks back into a single file"""
    in_dir = Path(input_dir) if input_dir else Path('.')
    chunks = sorted([f for f in in_dir.iterdir() if f.is_file() and f.name.startswith(prefix)])
    
    if not chunks:
        cprint(f'No chunks found with prefix: {prefix}', Color.RED)
        return
    
    cprint(f'Merging {len(chunks)} chunks into {output}', Color.CYAN, bold=True)
    
    total_bytes = 0
    with open(output, 'wb') as out:
        for chunk in chunks:
            with open(chunk, 'rb') as f:
                data = f.read()
                out.write(data)
                total_bytes += len(data)
            
            if verbose:
                cprint(f'Merged: {chunk.name} ({len(data)} bytes)', Color.GREEN)
    
    cprint(f'\nMerge complete. {total_bytes} bytes written to {output}', Color.GREEN, bold=True)


@file.command('encoding')
@click.argument('filepath', type=click.Path(exists=True))
@click.option('--convert', '-c', help='Convert to this encoding (e.g., utf-8, gbk)')
@click.option('--output', '-o', type=click.Path(), help='Output file for conversion')
@click.option('--force', '-f', is_flag=True, help='Overwrite output file if exists')
def file_encoding(filepath, convert, output, force):
    """Detect or convert file encoding"""
    encoding, confidence = _detect_encoding(filepath)
    
    cprint(f'Detected encoding: {encoding}', Color.CYAN, bold=True)
    cprint(f'Confidence: {confidence:.2%}', Color.CYAN)
    
    if not convert:
        return
    
    try:
        with open(filepath, 'rb') as f:
            content = f.read()
        
        decoded = content.decode(encoding, errors='replace')
        
        out_path = Path(output) if output else Path(filepath)
        if out_path.exists() and not force and output:
            cprint(f'Error: {output} already exists. Use --force to overwrite.', Color.RED)
            return
        
        with open(out_path, 'w', encoding=convert) as f:
            f.write(decoded)
        
        cprint(f'Converted to {convert}: {out_path}', Color.GREEN)
    except Exception as e:
        cprint(f'Conversion failed: {e}', Color.RED)


@file.group()
def csv():
    """CSV/TSV and JSON conversion commands"""
    pass


@csv.command('tojson')
@click.argument('filepath', type=click.Path(exists=True))
@click.option('--output', '-o', type=click.Path(), help='Output JSON file')
@click.option('--delimiter', '-d', default=',', show_default=True, help='CSV delimiter')
@click.option('--tsv', is_flag=True, help='Use tab as delimiter (TSV)')
@click.option('--indent', '-i', default=2, show_default=True, help='JSON indentation')
@click.option('--pretty', '-p', is_flag=True, help='Pretty print JSON')
def csv_tojson(filepath, output, delimiter, tsv, indent, pretty):
    """Convert CSV/TSV to JSON"""
    if tsv:
        delimiter = '\t'
    
    try:
        with open(filepath, 'r', encoding='utf-8-sig', newline='') as f:
            reader = csv.DictReader(f, delimiter=delimiter)
            data = list(reader)
    except Exception as e:
        cprint(f'Error reading CSV: {e}', Color.RED)
        return
    
    json_str = json.dumps(data, ensure_ascii=False, indent=indent if pretty else None)
    
    if output:
        with open(output, 'w', encoding='utf-8') as f:
            f.write(json_str)
        cprint(f'Converted {len(data)} rows to {output}', Color.GREEN)
    else:
        click.echo(json_str)


@csv.command('fromjson')
@click.argument('filepath', type=click.Path(exists=True))
@click.option('--output', '-o', type=click.Path(), help='Output CSV file')
@click.option('--delimiter', '-d', default=',', show_default=True, help='CSV delimiter')
@click.option('--tsv', is_flag=True, help='Use tab as delimiter (TSV)')
@click.option('--headers', help='Comma-separated list of headers')
def csv_fromjson(filepath, output, delimiter, tsv, headers):
    """Convert JSON array to CSV/TSV"""
    if tsv:
        delimiter = '\t'
    
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            data = json.load(f)
    except Exception as e:
        cprint(f'Error reading JSON: {e}', Color.RED)
        return
    
    if not isinstance(data, list):
        cprint('Error: JSON must be an array of objects', Color.RED)
        return
    
    if not data:
        cprint('Error: JSON array is empty', Color.YELLOW)
        return
    
    if headers:
        fieldnames = [h.strip() for h in headers.split(',')]
    else:
        fieldnames = set()
        for item in data:
            if isinstance(item, dict):
                fieldnames.update(item.keys())
        fieldnames = sorted(fieldnames)
    
    try:
        out_file = open(output, 'w', encoding='utf-8', newline='') if output else sys.stdout
        writer = csv.DictWriter(out_file, fieldnames=fieldnames, delimiter=delimiter, extrasaction='ignore')
        writer.writeheader()
        for item in data:
            if isinstance(item, dict):
                writer.writerow({k: str(v) if v is not None else '' for k, v in item.items()})
            else:
                writer.writerow({fieldnames[0]: str(item)})
        
        if output:
            out_file.close()
            cprint(f'Converted {len(data)} rows to {output}', Color.GREEN)
    except Exception as e:
        cprint(f'Error writing CSV: {e}', Color.RED)


@file.command('find')
@click.argument('name')
@click.argument('directory', required=False, type=click.Path(exists=True, file_okay=False))
@click.option('--type', '-t', default='all',
              type=click.Choice(['all', 'file', 'dir']),
              show_default=True, help='Search type')
@click.option('--glob', '-g', is_flag=True, help='Use glob pattern matching')
@click.option('--regex', '-r', is_flag=True, help='Use regex matching')
@click.option('--case-sensitive', '-s', is_flag=True, help='Case sensitive search')
@click.option('--size', help='Filter by size (e.g., +1M, -100K)')
def file_find(name, directory, type, glob, regex, case_sensitive, size):
    """Find files or directories by name"""
    path = Path(directory) if directory else Path('.')
    
    flags = 0 if case_sensitive else re.IGNORECASE
    
    if glob:
        pattern = re.compile(re.escape(name).replace(r'\*', '.*').replace(r'\?', '.'), flags)
    elif regex:
        pattern = re.compile(name, flags)
    else:
        pattern = re.compile(re.escape(name), flags)
    
    size_filter = None
    if size:
        op = size[0]
        size_value = size[1:]
        size_map = {'K': 1024, 'M': 1024 * 1024, 'G': 1024 * 1024 * 1024}
        if size_value[-1].upper() in size_map:
            size_bytes = int(size_value[:-1]) * size_map[size_value[-1].upper()]
        else:
            size_bytes = int(size_value)
        size_filter = (op, size_bytes)
    
    found = 0
    for root, dirs, files in os.walk(path):
        items = []
        if type in ['all', 'file']:
            items.extend([(f, True) for f in files])
        if type in ['all', 'dir']:
            items.extend([(d, False) for d in dirs])
        
        for item_name, is_file in items:
            if pattern.search(item_name):
                item_path = Path(root) / item_name
                
                if size_filter and is_file:
                    try:
                        file_size = item_path.stat().st_size
                        op, target = size_filter
                        if op == '+' and file_size <= target:
                            continue
                        if op == '-' and file_size >= target:
                            continue
                    except OSError:
                        continue
                
                cprint(f'{item_path}', Color.GREEN if is_file else Color.CYAN)
                found += 1
    
    cprint(f'\nFound {found} item(s)', Color.GREEN, bold=True)
