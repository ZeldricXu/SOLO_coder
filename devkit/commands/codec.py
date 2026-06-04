import base64 as base64_module
import hashlib
import hmac
import secrets
import string
import sys
import uuid as uuid_module
import time
import os
import random as random_module
from pathlib import Path
from urllib.parse import quote, unquote

import click

from ..core import Color, cprint


def _uuid_v7():
    uuid_bytes = bytearray(16)
    uuid_bytes[0:6] = int(time.time() * 1000).to_bytes(6, 'big')
    uuid_bytes[6] = 0x70 | (uuid_bytes[6] & 0x0F)
    uuid_bytes[8] = 0x80 | (uuid_bytes[8] & 0x3F)
    uuid_bytes[7:8] = os.urandom(1)
    uuid_bytes[9:16] = os.urandom(7)
    return uuid_module.UUID(bytes=bytes(uuid_bytes))


def read_input(filepath, content):
    if filepath:
        with open(filepath, 'rb') as f:
            return f.read()
    if content:
        return content.encode('utf-8')
    if not sys.stdin.isatty():
        return sys.stdin.buffer.read()
    return None


@click.group()
def codec():
    """Encoding/decoding and hash commands"""
    pass


@codec.group(name='base64')
def base64_group():
    """Base64 encode/decode commands"""
    pass


@base64_group.command('encode')
@click.argument('filepath', required=False, type=click.Path(exists=True))
@click.option('--content', '-c', help='Direct content string to encode')
@click.option('--url-safe', '-u', is_flag=True, help='Use URL-safe base64 encoding')
@click.option('--no-wrap', is_flag=True, help='Disable line wrapping')
def base64_encode(filepath, content, url_safe, no_wrap):
    """Base64 encode input"""
    data = read_input(filepath, content)
    if not data:
        cprint('Error: No input provided', Color.RED)
        return
    
    if url_safe:
        encoded = base64_module.urlsafe_b64encode(data)
    else:
        encoded = base64_module.b64encode(data)
    
    result = encoded.decode('ascii')
    if not no_wrap:
        result = '\n'.join([result[i:i+76] for i in range(0, len(result), 76)])
    
    click.echo(result)


@base64_group.command('decode')
@click.argument('filepath', required=False, type=click.Path(exists=True))
@click.option('--content', '-c', help='Direct content string to decode')
@click.option('--url-safe', '-u', is_flag=True, help='Use URL-safe base64 decoding')
@click.option('--output', '-o', type=click.Path(), help='Output file path')
def base64_decode(filepath, content, url_safe, output):
    """Base64 decode input"""
    if content:
        data = content.encode('ascii')
    elif filepath:
        with open(filepath, 'rb') as f:
            data = f.read()
    elif not sys.stdin.isatty():
        data = sys.stdin.buffer.read()
    else:
        cprint('Error: No input provided', Color.RED)
        return
    
    try:
        data = data.replace(b'\n', b'').replace(b'\r', b'').replace(b' ', b'')
        if url_safe:
            decoded = base64_module.urlsafe_b64decode(data)
        else:
            decoded = base64_module.b64decode(data)
    except Exception as e:
        cprint(f'Error decoding: {e}', Color.RED)
        return
    
    if output:
        with open(output, 'wb') as f:
            f.write(decoded)
        cprint(f'Decoded to {output}', Color.GREEN)
    else:
        try:
            click.echo(decoded.decode('utf-8'))
        except UnicodeDecodeError:
            click.echo(decoded)


@codec.group()
def url():
    """URL encode/decode commands"""
    pass


@url.command('encode')
@click.argument('text', required=False)
@click.option('--content', '-c', help='Direct content string to encode')
@click.option('--plus', '-p', is_flag=True, help='Encode spaces as + instead of %20')
def url_encode(text, content, plus):
    """URL encode input"""
    data = content or text
    if not data and not sys.stdin.isatty():
        data = sys.stdin.read()
    if not data:
        cprint('Error: No input provided', Color.RED)
        return
    
    if plus:
        encoded = quote(data, safe='')
    else:
        encoded = quote(data, safe='', encoding='utf-8')
        encoded = encoded.replace('+', '%20')
    
    click.echo(encoded)


@url.command('decode')
@click.argument('text', required=False)
@click.option('--content', '-c', help='Direct content string to decode')
def url_decode(text, content):
    """URL decode input"""
    data = content or text
    if not data and not sys.stdin.isatty():
        data = sys.stdin.read()
    if not data:
        cprint('Error: No input provided', Color.RED)
        return
    
    try:
        decoded = unquote(data)
    except Exception as e:
        cprint(f'Error decoding: {e}', Color.RED)
        return
    
    click.echo(decoded)


@codec.group(name='hex')
def hex_group():
    """Hex encode/decode commands"""
    pass


@hex_group.command('encode')
@click.argument('filepath', required=False, type=click.Path(exists=True))
@click.option('--content', '-c', help='Direct content string to encode')
@click.option('--spaces', '-s', is_flag=True, help='Add spaces between bytes')
def hex_encode(filepath, content, spaces):
    """Hex encode input"""
    data = read_input(filepath, content)
    if not data:
        cprint('Error: No input provided', Color.RED)
        return
    
    hex_str = data.hex()
    if spaces:
        hex_str = ' '.join([hex_str[i:i+2] for i in range(0, len(hex_str), 2)])
    
    click.echo(hex_str)


@hex_group.command('decode')
@click.argument('hex_str', required=False)
@click.option('--content', '-c', help='Direct hex string to decode')
@click.option('--output', '-o', type=click.Path(), help='Output file path')
def hex_decode(hex_str, content, output):
    """Hex decode input"""
    data = content or hex_str
    if not data and not sys.stdin.isatty():
        data = sys.stdin.read()
    if not data:
        cprint('Error: No input provided', Color.RED)
        return
    
    try:
        data = data.replace(' ', '').replace('\n', '').replace('\r', '')
        decoded = bytes.fromhex(data)
    except Exception as e:
        cprint(f'Error decoding: {e}', Color.RED)
        return
    
    if output:
        with open(output, 'wb') as f:
            f.write(decoded)
        cprint(f'Decoded to {output}', Color.GREEN)
    else:
        try:
            click.echo(decoded.decode('utf-8'))
        except UnicodeDecodeError:
            click.echo(decoded)


@codec.group(name='hash')
def hash_group():
    """Hash calculation commands"""
    pass


HASH_ALGORITHMS = ['md5', 'sha1', 'sha256', 'sha512', 'sha3_256', 'sha3_512']


@hash_group.command('calc')
@click.argument('filepath', required=False, type=click.Path(exists=True))
@click.option('--content', '-c', help='Direct content string to hash')
@click.option('--algorithm', '-a', default='sha256', 
              type=click.Choice(HASH_ALGORITHMS),
              show_default=True, help='Hash algorithm')
@click.option('--all', '-A', is_flag=True, help='Calculate all hash algorithms')
def hash_calc(filepath, content, algorithm, all):
    """Calculate hash for input"""
    data = read_input(filepath, content)
    if not data:
        cprint('Error: No input provided', Color.RED)
        return
    
    if all:
        for algo in HASH_ALGORITHMS:
            h = hashlib.new(algo)
            h.update(data)
            cprint(f'{algo:8}: ', Color.CYAN, nl=False)
            click.echo(h.hexdigest())
    else:
        h = hashlib.new(algorithm)
        h.update(data)
        click.echo(h.hexdigest())


@hash_group.command('hmac')
@click.argument('key')
@click.argument('filepath', required=False, type=click.Path(exists=True))
@click.option('--content', '-c', help='Direct content string')
@click.option('--algorithm', '-a', default='sha256',
              type=click.Choice(['md5', 'sha1', 'sha256', 'sha512']),
              show_default=True, help='HMAC algorithm')
def hash_hmac(key, filepath, content, algorithm):
    """Calculate HMAC for input with key"""
    data = read_input(filepath, content)
    if not data:
        cprint('Error: No input provided', Color.RED)
        return
    
    h = hmac.new(key.encode('utf-8'), data, algorithm)
    click.echo(h.hexdigest())


@codec.group(name='uuid')
def uuid_group():
    """UUID generation commands"""
    pass


@uuid_group.command('gen')
@click.option('--version', '-v', default='4',
              type=click.Choice(['1', '4', '7']),
              show_default=True, help='UUID version')
@click.option('--count', '-n', default=1, show_default=True, help='Number of UUIDs to generate')
@click.option('--uppercase', '-u', is_flag=True, help='Uppercase output')
def uuid_gen(version, count, uppercase):
    """Generate UUIDs (v1, v4, v7)"""
    for _ in range(count):
        if version == '1':
            u = uuid_module.uuid1()
        elif version == '7':
            u = _uuid_v7()
        else:
            u = uuid_module.uuid4()
        
        result = str(u)
        if uppercase:
            result = result.upper()
        click.echo(result)


@uuid_group.command('info')
@click.argument('uuid_str')
def uuid_info(uuid_str):
    """Parse and show UUID information"""
    try:
        u = uuid_module.UUID(uuid_str)
    except ValueError as e:
        cprint(f'Invalid UUID: {e}', Color.RED)
        return
    
    cprint('UUID: ', Color.CYAN, nl=False)
    click.echo(str(u))
    cprint('Version: ', Color.CYAN, nl=False)
    click.echo(u.version)
    cprint('Variant: ', Color.CYAN, nl=False)
    click.echo(u.variant)
    cprint('Hex: ', Color.CYAN, nl=False)
    click.echo(u.hex)
    cprint('Integer: ', Color.CYAN, nl=False)
    click.echo(u.int)
    
    if u.version == 1:
        cprint('Time: ', Color.CYAN, nl=False)
        click.echo(u.time)
        cprint('Node: ', Color.CYAN, nl=False)
        click.echo(u.node)
        cprint('Clock Seq: ', Color.CYAN, nl=False)
        click.echo(u.clock_seq)
    elif u.version == 7:
        cprint('Timestamp: ', Color.CYAN, nl=False)
        click.echo(u.time)


@codec.command(name='random')
@click.option('--length', '-l', default=32, show_default=True, help='Length of random string')
@click.option('--count', '-n', default=1, show_default=True, help='Number of strings to generate')
@click.option('--charset', '-c', default='mixed',
              type=click.Choice(['mixed', 'lower', 'upper', 'numbers', 'hex', 'custom']),
              show_default=True, help='Character set')
@click.option('--custom', help='Custom character set for --charset=custom')
@click.option('--no-special', is_flag=True, help='Exclude special characters from mixed charset')
def random_cmd(length, count, charset, custom, no_special):
    """Generate random strings"""
    if charset == 'lower':
        chars = string.ascii_lowercase
    elif charset == 'upper':
        chars = string.ascii_uppercase
    elif charset == 'numbers':
        chars = string.digits
    elif charset == 'hex':
        chars = string.hexdigits.lower()
    elif charset == 'custom':
        if not custom:
            cprint('Error: --custom required for --charset=custom', Color.RED)
            return
        chars = custom
    else:
        chars = string.ascii_letters + string.digits
        if not no_special:
            chars += '!@#$%^&*()_+-=[]{}|;:,.<>?'
    
    for _ in range(count):
        result = ''.join(secrets.choice(chars) for _ in range(length))
        click.echo(result)
