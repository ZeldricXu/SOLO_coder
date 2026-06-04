import json as json_module
import re
import time
import statistics
import sys
from pathlib import Path

import click
import yaml
from pygments import highlight
from pygments.lexers import JsonLexer, get_lexer_by_name
from pygments.formatters import TerminalFormatter

from ..core import Color, cprint, HttpClient, get_config


VARIABLE_PATTERN = re.compile(r'\{\{\s*(\w+)\s*\}\}')


def replace_variables(obj, variables):
    """Recursively replace {{ var }} placeholders in strings"""
    if isinstance(obj, str):
        def replacer(match):
            var_name = match.group(1)
            return str(variables.get(var_name, match.group(0)))
        return VARIABLE_PATTERN.sub(replacer, obj)
    elif isinstance(obj, dict):
        return {k: replace_variables(v, variables) for k, v in obj.items()}
    elif isinstance(obj, list):
        return [replace_variables(item, variables) for item in obj]
    return obj


def extract_value(data, path):
    """Extract value from dict using dot notation path (e.g., data.user.id)"""
    path = path.lstrip('.')
    parts = re.findall(r'\[(\d+)\]|([^.\[\]]+)', path)
    
    current = data
    for idx, key in parts:
        if idx:
            try:
                current = current[int(idx)]
            except (IndexError, KeyError, TypeError):
                return None
        else:
            try:
                current = current[key]
            except (KeyError, TypeError):
                return None
    return current


def load_collection_from_config(project=None):
    """Load API collection from config"""
    cfg = get_config()
    collections = cfg.get('api_collections', {})
    
    if project:
        if project not in collections:
            return None
        return collections[project]
    
    return collections


def run_assertions(response, assertions):
    """Run assertions against response"""
    results = []
    
    for assertion in assertions:
        if 'status_code' in assertion:
            expected = assertion['status_code']
            actual = response.status_code
            passed = expected == actual
            results.append({
                'name': f'Status code = {expected}',
                'passed': passed,
                'actual': actual,
                'expected': expected,
            })
        
        if 'json_path' in assertion:
            path = assertion['json_path']
            expected = assertion.get('value')
            operator = assertion.get('operator', 'eq')
            
            try:
                response_json = response.json()
            except Exception:
                response_json = None
            
            actual = extract_value(response_json, path) if response_json else None
            
            if operator == 'eq':
                passed = actual == expected
            elif operator == 'ne':
                passed = actual != expected
            elif operator == 'contains':
                passed = expected in str(actual)
            elif operator == 'gt':
                passed = actual > expected
            elif operator == 'lt':
                passed = actual < expected
            else:
                passed = actual == expected
            
            results.append({
                'name': f'JSON {path} {operator} {expected}',
                'passed': passed,
                'actual': actual,
                'expected': expected,
            })
    
    return results


def format_body(method, json_data, data, files):
    """Format request body for display"""
    if json_data is not None:
        try:
            return json_module.dumps(json_data, indent=2, ensure_ascii=False)
        except Exception:
            return str(json_data)
    elif data is not None:
        return str(data)[:1000]
    elif files:
        return f'<files: {list(files.keys())}>'
    return '<none>'


@click.group()
def api():
    """API testing tools"""
    pass


@api.command('test')
@click.argument('method', type=click.Choice(['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS']))
@click.argument('url')
@click.option('--header', '-H', 'headers', multiple=True, help='Header key:value')
@click.option('--json', '-j', 'json_data', help='JSON body')
@click.option('--data', '-d', help='Form/raw data')
@click.option('--timeout', '-t', default=30, type=int, help='Request timeout')
@click.option('--insecure', '-k', is_flag=True, help='Disable SSL verification')
@click.option('--verbose', '-v', is_flag=True, help='Show verbose output')
@click.option('--save-var', 'save_vars', multiple=True, help='Extract response to variable (name@path)')
@click.option('--assert-status', type=int, help='Assert status code')
@click.option('--assert-json', 'assert_jsons', multiple=True, help='Assert JSON path (path=value)')
def api_test(method, url, headers, json_data, data, timeout, insecure, verbose, save_vars, assert_status, assert_jsons):
    """Send API request and inspect response
    
    Examples:
      devkit api test GET https://api.example.com/users -H "Authorization: Bearer xxx"
      devkit api test POST https://api.example.com/users -j '{"name": "test"}' --assert-status 201
      devkit api test GET https://api.example.com/users/1 --save-var token@data.token
    """
    if not url.startswith(('http://', 'https://')):
        url = 'https://' + url
    
    header_dict = {}
    for h in headers:
        if ':' in h:
            key, value = h.split(':', 1)
            header_dict[key.strip()] = value.strip()
    
    body = None
    parsed_json = None
    if json_data:
        try:
            parsed_json = json_module.loads(json_data)
        except json_module.JSONDecodeError as e:
            cprint(f'Invalid JSON: {e}', Color.RED)
            return
    
    if verbose:
        cprint('> Request:', Color.CYAN, bold=True)
        cprint(f'  {method} {url}', Color.CYAN)
        for k, v in header_dict.items():
            cprint(f'  {k}: {v}', Color.CYAN)
        body_display = format_body(method, parsed_json, data, None)
        if body_display != '<none>':
            cprint(f'  Body: {body_display}', Color.CYAN)
        click.echo()
    
    client = HttpClient(timeout=timeout, verify_ssl=not insecure)
    
    try:
        start = time.time()
        
        if method == 'GET':
            response = client.get(url, headers=header_dict)
        elif method == 'POST':
            if parsed_json is not None:
                response = client.post(url, json=parsed_json, headers=header_dict)
            else:
                response = client.post(url, data=data, headers=header_dict)
        elif method == 'PUT':
            if parsed_json is not None:
                response = client.put(url, json=parsed_json, headers=header_dict)
            else:
                response = client.put(url, data=data, headers=header_dict)
        elif method == 'DELETE':
            response = client.delete(url, headers=header_dict)
        elif method == 'PATCH':
            if parsed_json is not None:
                response = client.patch(url, json=parsed_json, headers=header_dict)
            else:
                response = client.patch(url, data=data, headers=header_dict)
        elif method == 'HEAD':
            response = client.head(url, headers=header_dict)
        elif method == 'OPTIONS':
            response = client.options(url, headers=header_dict)
        
        elapsed_ms = (time.time() - start) * 1000
        
        status_color = Color.GREEN if response.status_code < 300 else Color.YELLOW if response.status_code < 400 else Color.RED
        cprint(f'Status: {response.status_code} {response.reason}', status_color, bold=True)
        cprint(f'Elapsed: {elapsed_ms:.2f}ms', Color.CYAN)
        
        if verbose or response.status_code >= 400:
            cprint('\nResponse Headers:', Color.CYAN)
            for k, v in response.headers.items():
                cprint(f'  {k}: {v}')
        
        content_type = response.headers.get('Content-Type', '')
        body_text = response.text
        
        if body_text:
            cprint('\nResponse Body:', Color.CYAN)
            if 'application/json' in content_type:
                try:
                    parsed = response.json()
                    formatted = json_module.dumps(parsed, indent=2, ensure_ascii=False)
                    colored = highlight(formatted, JsonLexer(), TerminalFormatter())
                    click.echo(colored)
                except Exception:
                    click.echo(body_text[:10000])
            else:
                click.echo(body_text[:10000])
        
        if save_vars or assert_status or assert_jsons:
            cfg = get_config()
            variables = cfg.get('api_variables', {})
            
            if save_vars:
                try:
                    response_json = response.json()
                except Exception:
                    response_json = {}
                
                for sv in save_vars:
                    if '@' in sv:
                        var_name, json_path = sv.split('@', 1)
                        value = extract_value(response_json, json_path)
                        variables[var_name] = value
                        cprint(f'\nSaved variable: {var_name} = {value}', Color.GREEN)
            
            cfg.set('api_variables', variables)
            
            assertions = []
            if assert_status is not None:
                assertions.append({'status_code': assert_status})
            for aj in assert_jsons:
                if '=' in aj:
                    path, expected = aj.split('=', 1)
                    try:
                        expected = json_module.loads(expected)
                    except Exception:
                        pass
                    assertions.append({'json_path': path, 'value': expected, 'operator': 'eq'})
            
            if assertions:
                cprint('\nAssertions:', Color.CYAN, bold=True)
                results = run_assertions(response, assertions)
                all_passed = True
                for r in results:
                    if r['passed']:
                        cprint(f'  ✓ {r["name"]}', Color.GREEN)
                    else:
                        cprint(f'  ✗ {r["name"]} (got: {r["actual"]})', Color.RED)
                        all_passed = False
                
                if not all_passed:
                    sys.exit(1)
    
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)
        if verbose:
            import traceback
            traceback.print_exc()
        sys.exit(1)


@api.command('run')
@click.argument('request_name')
@click.option('--project', '-p', help='Project name in collection')
@click.option('--verbose', '-v', is_flag=True, help='Show verbose output')
@click.option('--timeout', '-t', default=30, type=int, help='Request timeout')
@click.option('--insecure', '-k', is_flag=True, help='Disable SSL verification')
@click.option('--set-var', 'set_vars', multiple=True, help='Set variable for this run (name=value)')
def api_run(request_name, project, verbose, timeout, insecure, set_vars):
    """Run a pre-defined API request from collection
    
    Examples:
      devkit api run login --project myapp
      devkit api run get_users --project myapp --set-var user_id=123
    """
    cfg = get_config()
    collections = cfg.get('api_collections', {})
    
    if not collections:
        cprint('No API collections configured. Add with: devkit config set api_collections.myapp "..."', Color.RED)
        return
    
    if project:
        if project not in collections:
            cprint(f"Project '{project}' not found in collection", Color.RED)
            return
        project_collections = collections[project]
    else:
        project_collections = collections
    
    request_def = None
    if project:
        if isinstance(project_collections, dict) and request_name in project_collections:
            request_def = project_collections[request_name]
    else:
        for proj, reqs in project_collections.items():
            if isinstance(reqs, dict) and request_name in reqs:
                request_def = reqs[request_name]
                break
    
    if request_def is None:
        cprint(f"Request '{request_name}' not found in collection", Color.RED)
        return
    
    variables = cfg.get('api_variables', {})
    for sv in set_vars:
        if '=' in sv:
            name, value = sv.split('=', 1)
            variables[name] = value
    
    method = request_def.get('method', 'GET').upper()
    url = replace_variables(request_def.get('url', ''), variables)
    
    headers = {}
    for h in request_def.get('headers', []):
        if ':' in h:
            key, value = h.split(':', 1)
            headers[key.strip()] = replace_variables(value.strip(), variables)
    
    json_data = request_def.get('json')
    if json_data is not None:
        json_data = replace_variables(json_data, variables)
    
    if verbose:
        cprint(f'Running request: {request_name}', Color.CYAN, bold=True)
    
    ctx = click.get_current_context()
    params = {
        'method': method,
        'url': url,
        'headers': [f'{k}:{v}' for k, v in headers.items()],
        'json_data': json_module.dumps(json_data, ensure_ascii=False) if json_data else None,
        'data': request_def.get('data'),
        'timeout': timeout,
        'insecure': insecure,
        'verbose': verbose,
        'save_vars': request_def.get('extract', []),
        'assert_status': None,
        'assert_jsons': [],
    }
    
    for assertion in request_def.get('assertions', []):
        if 'status_code' in assertion:
            params['assert_status'] = assertion['status_code']
        if 'json_path' in assertion:
            op = assertion.get('operator', 'eq')
            val = assertion.get('value')
            if isinstance(val, (dict, list)):
                val_str = json_module.dumps(val)
            else:
                val_str = str(val)
            params['assert_jsons'] = list(params['assert_jsons']) + [f"{assertion['json_path']}={val_str}"]
    
    ctx.invoke(api_test, **params)


@api.command('perf')
@click.argument('method', type=click.Choice(['GET', 'POST', 'PUT', 'DELETE', 'PATCH']))
@click.argument('url')
@click.option('--header', '-H', 'headers', multiple=True)
@click.option('--json', '-j', 'json_data', help='JSON body')
@click.option('--requests', '-n', default=100, type=int, help='Number of requests')
@click.option('--concurrency', '-c', default=1, type=int, help='Concurrency level')
@click.option('--timeout', '-t', default=30, type=int)
@click.option('--insecure', '-k', is_flag=True)
def api_perf(method, url, headers, json_data, requests, concurrency, timeout, insecure):
    """Simple performance test: send N requests and show stats
    
    Examples:
      devkit api perf GET https://api.example.com/health -n 1000
      devkit api perf POST https://api.example.com/users -j '{"test": true}' -n 100 -c 10
    """
    if not url.startswith(('http://', 'https://')):
        url = 'https://' + url
    
    header_dict = {}
    for h in headers:
        if ':' in h:
            key, value = h.split(':', 1)
            header_dict[key.strip()] = value.strip()
    
    parsed_json = None
    if json_data:
        try:
            parsed_json = json_module.loads(json_data)
        except json_module.JSONDecodeError as e:
            cprint(f'Invalid JSON: {e}', Color.RED)
            return
    
    client = HttpClient(timeout=timeout, verify_ssl=not insecure)
    
    cprint(f'Performance test: {method} {url}', Color.CYAN, bold=True)
    cprint(f'Requests: {requests}, Concurrency: {concurrency}', Color.CYAN)
    click.echo()
    
    latencies = []
    errors = 0
    status_codes = {}
    
    import concurrent.futures
    
    def make_request():
        try:
            start = time.time()
            if method == 'GET':
                resp = client.get(url, headers=header_dict)
            elif method == 'POST':
                resp = client.post(url, json=parsed_json, headers=header_dict)
            elif method == 'PUT':
                resp = client.put(url, json=parsed_json, headers=header_dict)
            elif method == 'DELETE':
                resp = client.delete(url, headers=header_dict)
            else:
                resp = client.patch(url, json=parsed_json, headers=header_dict)
            elapsed = (time.time() - start) * 1000
            return elapsed, resp.status_code, None
        except Exception as e:
            return None, None, str(e)
    
    if concurrency == 1:
        for i in range(requests):
            elapsed, status, error = make_request()
            if error:
                errors += 1
            else:
                latencies.append(elapsed)
                status_codes[status] = status_codes.get(status, 0) + 1
            
            if (i + 1) % (requests // 10 if requests >= 10 else 1) == 0:
                cprint(f'Progress: {i+1}/{requests}', Color.YELLOW)
    else:
        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as executor:
            futures = [executor.submit(make_request) for _ in range(requests)]
            for i, future in enumerate(concurrent.futures.as_completed(futures)):
                elapsed, status, error = future.result()
                if error:
                    errors += 1
                else:
                    latencies.append(elapsed)
                    status_codes[status] = status_codes.get(status, 0) + 1
                
                if (i + 1) % (requests // 10 if requests >= 10 else 1) == 0:
                    cprint(f'Progress: {i+1}/{requests}', Color.YELLOW)
    
    click.echo()
    cprint('Results:', Color.CYAN, bold=True)
    cprint(f'  Total requests:  {requests}', Color.CYAN)
    cprint(f'  Successful:      {len(latencies)}', Color.CYAN)
    cprint(f'  Errors:          {errors}', Color.RED if errors else Color.CYAN)
    cprint(f'  Success rate:    {len(latencies)/requests*100:.2f}%', Color.CYAN)
    click.echo()
    
    if latencies:
        latencies.sort()
        p99 = latencies[int(len(latencies) * 0.99)] if len(latencies) >= 100 else latencies[-1]
        p95 = latencies[int(len(latencies) * 0.95)]
        p50 = latencies[int(len(latencies) * 0.50)]
        
        cprint('Latency (ms):', Color.GREEN, bold=True)
        cprint(f'  Min:    {min(latencies):.2f}', Color.GREEN)
        cprint(f'  Avg:    {statistics.mean(latencies):.2f}', Color.GREEN)
        cprint(f'  Max:    {max(latencies):.2f}', Color.GREEN)
        cprint(f'  P50:    {p50:.2f}', Color.GREEN)
        cprint(f'  P95:    {p95:.2f}', Color.YELLOW)
        cprint(f'  P99:    {p99:.2f}', Color.RED)
        click.echo()
    
    if status_codes:
        cprint('Status codes:', Color.CYAN, bold=True)
        for code, count in sorted(status_codes.items()):
            pct = count / len(latencies) * 100 if latencies else 0
            color = Color.GREEN if code < 400 else Color.RED
            cprint(f'  {code}: {count} ({pct:.1f}%)', color)


@api.command('collection')
@click.argument('action', type=click.Choice(['list', 'show', 'add', 'delete']))
@click.option('--project', '-p', help='Project name')
@click.option('--name', '-n', help='Request name')
@click.option('--file', '-f', type=click.Path(exists=True), help='YAML/JSON file with request definition')
def api_collection(action, project, name, file):
    """Manage API collection (Postman-like)
    
    Examples:
      devkit api collection list
      devkit api collection show --project myapp --name login
      devkit api collection add --project myapp --name login -f login.yml
      devkit api collection delete --project myapp --name login
    """
    cfg = get_config()
    collections = cfg.get('api_collections', {})
    
    if action == 'list':
        if not collections:
            cprint('No collections configured', Color.YELLOW)
            return
        for proj, reqs in collections.items():
            cprint(f'Project: {proj}', Color.CYAN, bold=True)
            if isinstance(reqs, dict):
                for req_name in reqs.keys():
                    cprint(f'  - {req_name}', Color.GREEN)
    
    elif action == 'show':
        if not project or not name:
            cprint('Please provide --project and --name', Color.RED)
            return
        if project not in collections or name not in collections.get(project, {}):
            cprint('Request not found', Color.RED)
            return
        click.echo(yaml.dump(collections[project][name], default_flow_style=False))
    
    elif action == 'add':
        if not project or not name or not file:
            cprint('Please provide --project, --name, and --file', Color.RED)
            return
        with open(file, 'r', encoding='utf-8') as f:
            request_def = yaml.safe_load(f)
        if project not in collections:
            collections[project] = {}
        collections[project][name] = request_def
        cfg.set('api_collections', collections)
        cprint(f'Added request: {project}.{name}', Color.GREEN)
    
    elif action == 'delete':
        if not project or not name:
            cprint('Please provide --project and --name', Color.RED)
            return
        if project in collections and name in collections[project]:
            del collections[project][name]
            if not collections[project]:
                del collections[project]
            cfg.set('api_collections', collections)
            cprint(f'Deleted request: {project}.{name}', Color.GREEN)
        else:
            cprint('Request not found', Color.RED)


@api.command('vars')
@click.argument('action', type=click.Choice(['list', 'clear', 'show', 'set', 'delete']), required=False, default='list')
@click.argument('name', required=False)
@click.argument('value', required=False)
def api_vars(action, name, value):
    """Manage saved API variables
    
    Examples:
      devkit api vars list
      devkit api vars show token
      devkit api vars set token xxx
      devkit api vars delete token
      devkit api vars clear
    """
    cfg = get_config()
    variables = cfg.get('api_variables', {})
    
    if action == 'list':
        if not variables:
            cprint('No variables saved', Color.YELLOW)
            return
        cprint('Saved variables:', Color.CYAN, bold=True)
        for k, v in variables.items():
            cprint(f'  {k}: ', Color.CYAN, nl=False)
            cprint(str(v), Color.GREEN)
    
    elif action == 'show':
        if not name:
            cprint('Please provide variable name', Color.RED)
            return
        if name in variables:
            click.echo(variables[name])
        else:
            cprint(f'Variable not found: {name}', Color.RED)
    
    elif action == 'set':
        if not name or value is None:
            cprint('Please provide variable name and value', Color.RED)
            return
        variables[name] = value
        cfg.set('api_variables', variables)
        cprint(f'Set {name} = {value}', Color.GREEN)
    
    elif action == 'delete':
        if not name:
            cprint('Please provide variable name', Color.RED)
            return
        if name in variables:
            del variables[name]
            cfg.set('api_variables', variables)
            cprint(f'Deleted {name}', Color.GREEN)
        else:
            cprint(f'Variable not found: {name}', Color.RED)
    
    elif action == 'clear':
        cfg.set('api_variables', {})
        cprint('All variables cleared', Color.GREEN)
