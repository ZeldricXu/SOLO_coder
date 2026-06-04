import asyncio
import json as json_module
import socket
import sys
import time
from pathlib import Path
from typing import Dict, Optional

import click
import dns.resolver as dns_resolver
import requests

from ..core import Color, cprint, HttpClient


@click.group()
def net():
    """Network diagnostic commands"""
    pass


@net.command('portcheck')
@click.argument('port', type=int)
@click.option('--host', '-H', default='localhost', show_default=True, help='Host to check')
@click.option('--timeout', '-t', default=2, show_default=True, help='Timeout in seconds')
def portcheck(port, host, timeout):
    """Check if a TCP port is open"""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        start_time = time.time()
        result = sock.connect_ex((host, port))
        elapsed = (time.time() - start_time) * 1000
        sock.close()
        
        if result == 0:
            cprint(f'Port {port} on {host} is ', Color.GREEN, nl=False)
            cprint('OPEN', Color.GREEN, bold=True, nl=False)
            cprint(f' (latency: {elapsed:.2f}ms)', Color.CYAN)
        else:
            cprint(f'Port {port} on {host} is ', Color.RED, nl=False)
            cprint('CLOSED', Color.RED, bold=True)
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)


@net.command('http')
@click.argument('url')
@click.option('--method', '-X', default='GET',
              type=click.Choice(['GET', 'POST', 'PUT', 'DELETE', 'PATCH', 'HEAD', 'OPTIONS']),
              show_default=True, help='HTTP method')
@click.option('--header', '-H', 'headers', multiple=True, help='Request header (key:value)')
@click.option('--data', '-d', help='Request body data')
@click.option('--json', '-j', 'json_data', help='JSON body data')
@click.option('--file', '-f', type=click.Path(exists=True), help='File to send as body')
@click.option('--output', '-o', type=click.Path(), help='Output file for response body')
@click.option('--timeout', '-t', default=30, show_default=True, help='Request timeout')
@click.option('--insecure', '-k', is_flag=True, help='Disable SSL verification')
@click.option('--verbose', '-v', is_flag=True, help='Show verbose output')
def http(url, method, headers, data, json_data, file, output, timeout, insecure, verbose):
    """Send HTTP requests (curl alternative)"""
    if not url.startswith(('http://', 'https://')):
        url = 'http://' + url
    
    header_dict = {}
    for h in headers:
        if ':' in h:
            key, value = h.split(':', 1)
            header_dict[key.strip()] = value.strip()
    
    body = None
    if json_data:
        try:
            body = json_module.loads(json_data)
            header_dict.setdefault('Content-Type', 'application/json')
        except json_module.JSONDecodeError as e:
            cprint(f'Invalid JSON: {e}', Color.RED)
            return
    elif data:
        body = data
    elif file:
        with open(file, 'rb') as f:
            body = f.read()
    
    client = HttpClient(timeout=timeout, verify_ssl=not insecure)
    
    try:
        start_time = time.time()
        if method == 'GET':
            response = client.get(url, headers=header_dict)
        elif method == 'POST':
            if isinstance(body, dict):
                response = client.post(url, json=body, headers=header_dict)
            else:
                response = client.post(url, data=body, headers=header_dict)
        elif method == 'PUT':
            if isinstance(body, dict):
                response = client.put(url, json=body, headers=header_dict)
            else:
                response = client.put(url, data=body, headers=header_dict)
        elif method == 'DELETE':
            response = client.delete(url, headers=header_dict)
        elif method == 'PATCH':
            if isinstance(body, dict):
                response = client.patch(url, json=body, headers=header_dict)
            else:
                response = client.patch(url, data=body, headers=header_dict)
        elif method == 'HEAD':
            response = client.head(url, headers=header_dict)
        elif method == 'OPTIONS':
            response = client.options(url, headers=header_dict)
        
        elapsed = (time.time() - start_time) * 1000
    except requests.exceptions.SSLError as e:
        cprint(f'SSL Error: {e}', Color.RED)
        cprint('Use -k to skip SSL verification', Color.YELLOW)
        return
    except requests.exceptions.ConnectionError as e:
        cprint(f'Connection Error: {e}', Color.RED)
        return
    except requests.exceptions.Timeout as e:
        cprint(f'Request Timeout: {e}', Color.RED)
        return
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)
        return
    
    if verbose:
        cprint('> Request:', Color.CYAN, bold=True)
        cprint(f'  {method} {response.request.url}', Color.CYAN)
        for k, v in response.request.headers.items():
            cprint(f'  {k}: {v}', Color.CYAN)
        if body and isinstance(body, (dict, str)):
            cprint(f'  Body: {body[:200]}', Color.CYAN)
        
        cprint('\n< Response:', Color.GREEN, bold=True)
    
    status_color = Color.GREEN if response.status_code < 300 else Color.YELLOW if response.status_code < 400 else Color.RED
    cprint(f'Status: {response.status_code} {response.reason}', status_color, bold=True)
    
    if verbose or response.status_code >= 400:
        cprint(f'Elapsed: {elapsed:.2f}ms', Color.CYAN)
        cprint(f'Size: {len(response.content)} bytes', Color.CYAN)
        cprint('\nHeaders:', Color.CYAN)
        for k, v in response.headers.items():
            cprint(f'  {k}: {v}')
    
    if output:
        with open(output, 'wb') as f:
            f.write(response.content)
        cprint(f'\nResponse saved to {output}', Color.GREEN)
    else:
        content_type = response.headers.get('Content-Type', '')
        if 'application/json' in content_type:
            try:
                parsed = response.json()
                cprint('\nBody:', Color.CYAN)
                click.echo(json_module.dumps(parsed, ensure_ascii=False, indent=2))
            except json_module.JSONDecodeError:
                click.echo(response.text)
        else:
            if response.text:
                cprint('\nBody:', Color.CYAN)
                click.echo(response.text[:10000])


@net.group()
def dns():
    """DNS resolution commands"""
    pass


@dns.command('resolve')
@click.argument('domain')
@click.option('--type', '-t', default='A',
              type=click.Choice(['A', 'AAAA', 'MX', 'TXT', 'CNAME', 'NS', 'SOA', 'PTR']),
              show_default=True, help='DNS record type')
@click.option('--server', '-s', help='DNS server to use')
@click.option('--timeout', '-o', default=5, show_default=True, help='Query timeout')
def dns_resolve(domain, type, server, timeout):
    """Resolve DNS records"""
    try:
        resolver = dns_resolver.Resolver()
        resolver.timeout = timeout
        if server:
            resolver.nameservers = [server]
        
        answers = resolver.resolve(domain, type)
        
        cprint(f'{type} records for {domain}:', Color.CYAN, bold=True)
        for rdata in answers:
            if type == 'A':
                cprint(f'  {rdata.address}', Color.GREEN)
            elif type == 'AAAA':
                cprint(f'  {rdata.address}', Color.GREEN)
            elif type == 'MX':
                cprint(f'  Priority: {rdata.preference}, Exchange: {rdata.exchange}', Color.GREEN)
            elif type == 'TXT':
                cprint(f'  {"".join(s.decode() for s in rdata.strings)}', Color.GREEN)
            elif type == 'CNAME':
                cprint(f'  {rdata.target}', Color.GREEN)
            elif type == 'NS':
                cprint(f'  {rdata.target}', Color.GREEN)
            elif type == 'SOA':
                cprint(f'  MNAME: {rdata.mname}', Color.GREEN)
                cprint(f'  RNAME: {rdata.rname}', Color.GREEN)
                cprint(f'  Serial: {rdata.serial}', Color.GREEN)
            else:
                cprint(f'  {rdata}', Color.GREEN)
    except dns_resolver.NXDOMAIN:
        cprint(f'Domain {domain} does not exist', Color.RED)
    except dns_resolver.NoAnswer:
        cprint(f'No {type} records found for {domain}', Color.YELLOW)
    except dns_resolver.Timeout:
        cprint(f'DNS query timed out', Color.RED)
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)


@dns.command('all')
@click.argument('domain')
@click.option('--server', '-s', help='DNS server to use')
def dns_all(domain, server):
    """Query all common DNS record types"""
    types = ['A', 'AAAA', 'MX', 'TXT', 'CNAME', 'NS']
    for t in types:
        try:
            resolver = dns_resolver.Resolver()
            if server:
                resolver.nameservers = [server]
            
            answers = resolver.resolve(domain, t)
            
            cprint(f'\n{t} records:', Color.CYAN, bold=True)
            for rdata in answers:
                if t == 'MX':
                    cprint(f'  {rdata.preference} {rdata.exchange}', Color.GREEN)
                elif t == 'TXT':
                    cprint(f'  {"".join(s.decode() for s in rdata.strings)}', Color.GREEN)
                else:
                    cprint(f'  {rdata}', Color.GREEN)
        except Exception:
            pass


@net.command('iplookup')
@click.argument('ip', required=False)
@click.option('--url', '-u', help='API endpoint for IP lookup')
def iplookup(ip, url):
    """Query IP address geolocation"""
    if not ip:
        try:
            response = requests.get('https://api.ipify.org?format=json', timeout=5)
            ip = response.json()['ip']
            cprint(f'Your public IP: {ip}', Color.CYAN)
        except Exception as e:
            cprint(f'Could not detect IP: {e}', Color.RED)
            return
    
    try:
        if url:
            api_url = f'{url}/{ip}'
        else:
            api_url = f'https://ipapi.co/{ip}/json/'
        
        response = requests.get(api_url, timeout=10)
        data = response.json()
        
        if 'error' in data:
            cprint(f'Error: {data.get("reason", "Unknown error")}', Color.RED)
            return
        
        fields = [
            ('IP', 'ip'),
            ('Version', 'version'),
            ('City', 'city'),
            ('Region', 'region'),
            ('Country', 'country_name'),
            ('Country Code', 'country'),
            ('Postal Code', 'postal'),
            ('Latitude', 'latitude'),
            ('Longitude', 'longitude'),
            ('Timezone', 'timezone'),
            ('UTC Offset', 'utc_offset'),
            ('ISP', 'org'),
            ('ASN', 'asn'),
        ]
        
        cprint(f'IP Information for {ip}:', Color.CYAN, bold=True)
        for label, key in fields:
            value = data.get(key)
            if value:
                cprint(f'  {label:15}: ', Color.CYAN, nl=False)
                cprint(str(value), Color.GREEN)
    except requests.exceptions.RequestException as e:
        cprint(f'Network error: {e}', Color.RED)
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)


@net.command('wstest')
@click.argument('url')
@click.option('--message', '-m', default='ping', show_default=True, help='Message to send')
@click.option('--timeout', '-t', default=10, show_default=True, help='Connection timeout')
@click.option('--header', '-H', 'headers', multiple=True, help='Connection header (key:value)')
def wstest(url, message, timeout, headers):
    """Test WebSocket connection"""
    header_dict = {}
    for h in headers:
        if ':' in h:
            key, value = h.split(':', 1)
            header_dict[key.strip()] = value.strip()
    
    try:
        import websockets
    except ImportError:
        cprint('Error: websockets library not installed. Run: pip install websockets', Color.RED)
        return
    
    async def test_ws():
        try:
            cprint(f'Connecting to {url}...', Color.CYAN)
            
            async with websockets.connect(url, extra_headers=header_dict, open_timeout=timeout) as ws:
                cprint('Connected!', Color.GREEN, bold=True)
                
                cprint(f'Sending: {message}', Color.CYAN)
                await ws.send(message)
                
                try:
                    response = await asyncio.wait_for(ws.recv(), timeout=timeout)
                    cprint(f'Received: {response}', Color.GREEN)
                except asyncio.TimeoutError:
                    cprint('No response received (timeout)', Color.YELLOW)
                
                cprint('Connection closed', Color.CYAN)
        except websockets.exceptions.InvalidURI:
            cprint('Error: Invalid WebSocket URL', Color.RED)
        except websockets.exceptions.InvalidStatusCode as e:
            cprint(f'Error: Server returned status {e.status_code}', Color.RED)
        except asyncio.TimeoutError:
            cprint('Error: Connection timeout', Color.RED)
        except Exception as e:
            cprint(f'Error: {e}', Color.RED)
    
    asyncio.get_event_loop().run_until_complete(test_ws())
