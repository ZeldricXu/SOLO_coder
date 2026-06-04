import json
import os
import re
import subprocess
import sys
from collections import defaultdict
from datetime import datetime, timedelta
from pathlib import Path

import click
import requests

from ..core import Color, cprint, HttpClient


def _get_repo(path=None):
    try:
        import git
        repo_path = path or os.getcwd()
        repo = git.Repo(repo_path, search_parent_directories=True)
        return repo
    except ImportError:
        cprint('Error: GitPython not installed. Run: pip install GitPython', Color.RED)
        return None
    except Exception as e:
        cprint(f'Error: Not a git repository: {e}', Color.RED)
        return None


@click.group()
def git():
    """Git helper commands"""
    pass


@git.command('stats')
@click.option('--path', '-p', type=click.Path(exists=True), help='Repository path (default: current)')
@click.option('--since', help='Since date (e.g., 2024-01-01 or "1 month ago")')
@click.option('--until', help='Until date')
@click.option('--author', '-a', help='Filter by author')
@click.option('--top', '-n', type=int, default=10, show_default=True, help='Top N contributors')
@click.option('--by-month', '-m', is_flag=True, help='Show monthly statistics')
def git_stats(path, since, until, author, top, by_month):
    """Show commit statistics per contributor"""
    repo = _get_repo(path)
    if not repo:
        return
    
    try:
        kwargs = {}
        if since:
            kwargs['since'] = since
        if until:
            kwargs['until'] = until
        if author:
            kwargs['author'] = author
        
        commits = list(repo.iter_commits(**kwargs))
        
        if not commits:
            cprint('No commits found', Color.YELLOW)
            return
        
        author_stats = defaultdict(lambda: {'commits': 0, 'additions': 0, 'deletions': 0})
        monthly_stats = defaultdict(lambda: {'commits': 0, 'additions': 0, 'deletions': 0})
        
        for commit in commits:
            name = commit.author.name
            email = commit.author.email
            
            try:
                diff = commit.stats.total
                additions = diff.get('insertions', 0)
                deletions = diff.get('deletions', 0)
            except Exception:
                additions = 0
                deletions = 0
            
            author_stats[name]['commits'] += 1
            author_stats[name]['additions'] += additions
            author_stats[name]['deletions'] += deletions
            
            if by_month:
                month = datetime.fromtimestamp(commit.committed_date).strftime('%Y-%m')
                monthly_stats[month]['commits'] += 1
                monthly_stats[month]['additions'] += additions
                monthly_stats[month]['deletions'] += deletions
        
        sorted_authors = sorted(
            author_stats.items(),
            key=lambda x: (-x[1]['commits'], -x[1]['additions'])
        )[:top]
        
        total_commits = len(commits)
        total_additions = sum(s['additions'] for s in author_stats.values())
        total_deletions = sum(s['deletions'] for s in author_stats.values())
        
        cprint(f'Git Statistics ({total_commits} commits)', Color.CYAN, bold=True)
        if since or until:
            range_str = ''
            if since:
                range_str += f' since {since}'
            if until:
                range_str += f' until {until}'
            cprint(f'Range: {range_str.strip()}', Color.CYAN)
        cprint(f'Total additions: {total_additions}, deletions: {total_deletions}', Color.CYAN)
        cprint(f'\nTop {min(top, len(sorted_authors))} Contributors:', Color.CYAN, bold=True)
        
        for i, (name, stats) in enumerate(sorted_authors, 1):
            commit_pct = (stats["commits"] / total_commits * 100) if total_commits else 0
            bar = '█' * int(commit_pct / 5)
            cprint(f'{i:2}. {name:20} ', Color.GREEN, nl=False)
            cprint(f'commits: {stats["commits"]:4} ({commit_pct:5.1f}%) ', Color.YELLOW, nl=False)
            cprint(f'+{stats["additions"]:5} -{stats["deletions"]:5} ', Color.CYAN, nl=False)
            cprint(bar, Color.MAGENTA)
        
        if by_month and monthly_stats:
            cprint(f'\nMonthly Statistics:', Color.CYAN, bold=True)
            for month in sorted(monthly_stats.keys()):
                stats = monthly_stats[month]
                cprint(f'  {month}: ', Color.GREEN, nl=False)
                cprint(f'{stats["commits"]:4} commits, ', Color.YELLOW, nl=False)
                cprint(f'+{stats["additions"]:5} -{stats["deletions"]:5}', Color.CYAN)
    
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)


@git.command('changelog')
@click.option('--path', '-p', type=click.Path(exists=True), help='Repository path (default: current)')
@click.option('--since', help='Since date or tag (e.g., v1.0.0 or "2024-01-01")')
@click.option('--until', help='Until date or tag')
@click.option('--output', '-o', type=click.Path(), help='Output file path')
@click.option('--format', '-f', default='markdown',
              type=click.Choice(['markdown', 'plain', 'json']),
              show_default=True, help='Output format')
@click.option('--group-by-type', '-g', is_flag=True, help='Group commits by type')
def git_changelog(path, since, until, output, format, group_by_type):
    """Generate CHANGELOG from commit history"""
    repo = _get_repo(path)
    if not repo:
        return
    
    try:
        kwargs = {}
        if since:
            if since in repo.tags:
                kwargs['rev'] = f'{since}..HEAD'
            else:
                kwargs['since'] = since
        if until:
            if until in repo.tags:
                kwargs['rev'] = kwargs.get('rev', 'HEAD').replace('HEAD', until)
            else:
                kwargs['until'] = until
        
        commits = list(repo.iter_commits(**kwargs))
        
        if not commits:
            cprint('No commits found', Color.YELLOW)
            return
        
        commit_types = {
            'feat': 'Features',
            'fix': 'Bug Fixes',
            'docs': 'Documentation',
            'style': 'Style',
            'refactor': 'Refactoring',
            'perf': 'Performance',
            'test': 'Tests',
            'build': 'Build',
            'ci': 'CI/CD',
            'chore': 'Chores',
            'other': 'Other Changes'
        }
        
        grouped = defaultdict(list)
        for commit in commits:
            message = commit.message.strip().split('\n')[0]
            match = re.match(r'^(\w+)(\(\w+\))?:\s*(.+)', message)
            if match:
                ctype = match.group(1).lower()
                ctype = ctype if ctype in commit_types else 'other'
                desc = match.group(3)
            else:
                ctype = 'other'
                desc = message
            
            date = datetime.fromtimestamp(commit.committed_date).strftime('%Y-%m-%d')
            grouped[ctype].append({
                'hash': commit.hexsha[:7],
                'author': commit.author.name,
                'date': date,
                'message': desc
            })
        
        if format == 'json':
            output_data = []
            for ctype, items in grouped.items():
                for item in items:
                    item['type'] = ctype
                    output_data.append(item)
            content = json.dumps(output_data, ensure_ascii=False, indent=2)
        elif format == 'plain':
            lines = []
            for ctype, items in grouped.items():
                if items:
                    lines.append(f'[{commit_types.get(ctype, ctype)}]')
                    for item in items:
                        lines.append(f'  {item["hash"]} {item["date"]} {item["message"]}')
                    lines.append('')
            content = '\n'.join(lines)
        else:
            lines = []
            title = 'CHANGELOG'
            lines.append(f'# {title}')
            lines.append('')
            
            tag_line = ''
            if since:
                tag_line += f' from {since}'
            if until:
                tag_line += f' to {until}'
            if tag_line:
                lines.append(f'*{datetime.now().strftime("%Y-%m-%d")}*{tag_line}')
                lines.append('')
            
            if group_by_type:
                for ctype in ['feat', 'fix', 'docs', 'style', 'refactor', 'perf', 'test', 'build', 'ci', 'chore', 'other']:
                    items = grouped.get(ctype, [])
                    if items:
                        lines.append(f'## {commit_types[ctype]}')
                        lines.append('')
                        for item in items:
                            lines.append(f'- **{item["hash"]}** ({item["date"]}) {item["message"]}')
                        lines.append('')
            else:
                lines.append('## All Commits')
                lines.append('')
                for ctype, items in grouped.items():
                    for item in items:
                        lines.append(f'- **{item["hash"]}** ({item["date"]}) {commit_types.get(ctype, ctype)}: {item["message"]}')
                lines.append('')
            
            content = '\n'.join(lines)
        
        if output:
            with open(output, 'w', encoding='utf-8') as f:
                f.write(content)
            cprint(f'Changelog written to {output}', Color.GREEN)
        else:
            click.echo(content)
    
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)


@git.command('clean-branches')
@click.option('--path', '-p', type=click.Path(exists=True), help='Repository path (default: current)')
@click.option('--merged-to', default='main', show_default=True, help='Target branch to check merge status')
@click.option('--exclude', '-e', multiple=True, help='Branch pattern to exclude (can use multiple)')
@click.option('--force', '-f', is_flag=True, help='Force delete branches')
@click.option('--dry-run', '-n', is_flag=True, help='Show what would be deleted')
def git_clean_branches(path, merged_to, exclude, force, dry_run):
    """Clean up local branches that have been merged"""
    repo = _get_repo(path)
    if not repo:
        return
    
    try:
        try:
            repo.git.fetch('--all', '--prune')
        except Exception:
            pass
        
        try:
            repo.heads[merged_to]
        except (IndexError, KeyError):
            try:
                merged_to = 'master'
                repo.heads[merged_to]
            except (IndexError, KeyError):
                cprint(f'Error: Branch {merged_to} not found', Color.RED)
                return
        
        try:
            merged_output = repo.git.branch('--merged', merged_to)
        except Exception as e:
            cprint(f'Error checking merged branches: {e}', Color.RED)
            return
        
        merged_branches = []
        for line in merged_output.split('\n'):
            branch = line.strip().lstrip('* ').strip()
            if branch and branch not in [merged_to, 'master', 'main']:
                if exclude:
                    skip = False
                    for pattern in exclude:
                        if re.search(pattern, branch):
                            skip = True
                            break
                    if skip:
                        continue
                merged_branches.append(branch)
        
        if not merged_branches:
            cprint('No merged branches to clean up', Color.GREEN)
            return
        
        cprint(f'Merged branches (will be deleted):', Color.CYAN, bold=True)
        for branch in merged_branches:
            cprint(f'  - {branch}', Color.YELLOW)
        
        if dry_run:
            cprint(f'\nWould delete {len(merged_branches)} branch(es)', Color.GREEN)
            return
        
        if not click.confirm(f'\nDelete {len(merged_branches)} branch(es)?', default=True):
            cprint('Cancelled', Color.YELLOW)
            return
        
        deleted = []
        failed = []
        for branch in merged_branches:
            try:
                delete_flag = '-D' if force else '-d'
                repo.git.branch(delete_flag, branch)
                deleted.append(branch)
            except Exception as e:
                failed.append((branch, str(e)))
        
        if deleted:
            cprint(f'\nDeleted {len(deleted)} branch(es):', Color.GREEN, bold=True)
            for branch in deleted:
                cprint(f'  - {branch}', Color.GREEN)
        
        if failed:
            cprint(f'\nFailed to delete {len(failed)} branch(es):', Color.RED, bold=True)
            for branch, error in failed:
                cprint(f'  - {branch}: {error}', Color.RED)
    
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)


@git.command('file-history')
@click.argument('filepath')
@click.option('--path', '-p', type=click.Path(exists=True), help='Repository path (default: current)')
@click.option('--limit', '-n', type=int, default=20, show_default=True, help='Maximum number of commits')
@click.option('--patch', '-P', is_flag=True, help='Show diff patches')
def git_file_history(filepath, path, limit, patch):
    """Show modification history of a file"""
    repo = _get_repo(path)
    if not repo:
        return
    
    try:
        file_path = Path(filepath)
        if not file_path.is_absolute():
            file_path = Path(repo.working_dir) / filepath
        
        commits = list(repo.iter_commits(paths=str(filepath), max_count=limit))
        
        if not commits:
            cprint(f'No history found for {filepath}', Color.YELLOW)
            return
        
        cprint(f'File history: {filepath}', Color.CYAN, bold=True)
        cprint(f'Showing last {len(commits)} commits\n', Color.CYAN)
        
        for i, commit in enumerate(commits, 1):
            date = datetime.fromtimestamp(commit.committed_date).strftime('%Y-%m-%d %H:%M:%S')
            message = commit.message.strip().split('\n')[0]
            
            cprint(f'{i:3}. {commit.hexsha[:7]} ', Color.GREEN, nl=False)
            cprint(f'by {commit.author.name} ', Color.YELLOW, nl=False)
            cprint(f'at {date}', Color.CYAN)
            cprint(f'     {message}', Color.MAGENTA)
            
            if patch:
                try:
                    diff = repo.git.show(f'{commit.hexsha}', '--', str(filepath))
                    lines = diff.split('\n')
                    for line in lines[:50]:
                        if line.startswith('+') and not line.startswith('+++'):
                            cprint(f'     {line}', Color.GREEN)
                        elif line.startswith('-') and not line.startswith('---'):
                            cprint(f'     {line}', Color.RED)
                        elif line.startswith('@@'):
                            cprint(f'     {line}', Color.CYAN)
                        elif line.startswith('diff') or line.startswith('index') or line.startswith('---') or line.startswith('+++'):
                            continue
                        else:
                            cprint(f'     {line}')
                    if len(lines) > 50:
                        cprint(f'     ... (truncated, {len(lines) - 50} more lines)', Color.YELLOW)
                    click.echo()
                except Exception:
                    pass
    
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)


@git.command('gitignore')
@click.argument('template', required=False)
@click.option('--list', '-l', is_flag=True, help='List available templates')
@click.option('--output', '-o', default='.gitignore', show_default=True, help='Output file')
@click.option('--force', '-f', is_flag=True, help='Overwrite existing file')
@click.option('--append', '-a', is_flag=True, help='Append to existing file')
def git_gitignore(template, list, output, force, append):
    """Generate .gitignore from GitHub templates"""
    if list:
        try:
            client = HttpClient(timeout=10)
            response = client.get('https://api.github.com/repos/github/gitignore/contents')
            templates = [f['name'].replace('.gitignore', '') for f in response.json() 
                        if f['name'].endswith('.gitignore') and f['type'] == 'file']
            
            cprint('Available templates:', Color.CYAN, bold=True)
            for i, t in enumerate(sorted(templates), 1):
                cprint(f'  {t:25} ', Color.GREEN, nl=False)
                if i % 3 == 0:
                    click.echo()
            click.echo()
        except Exception as e:
            cprint(f'Error fetching templates: {e}', Color.RED)
        return
    
    if not template:
        cprint('Error: Template name required (use --list to see available)', Color.RED)
        return
    
    try:
        client = HttpClient(timeout=10)
        url = f'https://raw.githubusercontent.com/github/gitignore/main/{template}.gitignore'
        response = client.get(url)
        
        if response.status_code != 200:
            cprint(f'Template not found: {template}', Color.RED)
            cprint('Use --list to see available templates', Color.YELLOW)
            return
        
        content = f'# .gitignore generated from {template} template by devkit\n'
        content += f'# Source: {url}\n'
        content += response.text
        
        out_path = Path(output)
        if out_path.exists():
            if append:
                with open(out_path, 'a', encoding='utf-8') as f:
                    f.write('\n' + content)
                cprint(f'Appended to {output}', Color.GREEN)
            elif force:
                with open(out_path, 'w', encoding='utf-8') as f:
                    f.write(content)
                cprint(f'Overwritten {output}', Color.GREEN)
            else:
                cprint(f'Error: {output} already exists. Use --force or --append.', Color.RED)
        else:
            with open(out_path, 'w', encoding='utf-8') as f:
                f.write(content)
            cprint(f'Created {output}', Color.GREEN)
    
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)
