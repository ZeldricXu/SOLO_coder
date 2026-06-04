import warnings
import sys
from pathlib import Path

warnings.filterwarnings('ignore', category=DeprecationWarning)
warnings.filterwarnings('ignore', message='urllib3.*NotOpenSSLWarning.*')
warnings.filterwarnings('ignore', message='.*NotOpenSSLWarning.*')

import urllib3
if hasattr(urllib3, 'disable_warnings'):
    urllib3.disable_warnings()

import click

from . import __version__
from .core import Color, cprint, get_config
from .commands.json_cmd import json
from .commands.codec import codec
from .commands.crypto import crypto
from .commands.net import net
from .commands.time_cmd import time_group as time
from .commands.regex_cmd import regex
from .commands.file_cmd import file
from .commands.git_cmd import git
from .commands.codegen import codegen
from .commands.db import db
from .commands.api import api
from .commands.sysmon import sysmon


@click.group()
@click.version_option(version=__version__, prog_name='devkit')
@click.option('--no-color', is_flag=True, help='Disable colored output')
@click.option('--config', type=click.Path(), help='Path to custom config file')
@click.pass_context
def cli(ctx, no_color, config):
    """一站式开发者命令行工具箱
    
    集成日常开发中常用的工具集：
    
    \b
    - 数据处理：JSON/YAML/TOML 格式化、转换、Diff、查询
    - 编解码：Base64、URL、Hex、Hash、UUID、JWT
    - 加密安全：AES/RSA 加密、JWT 签发验证、证书、密码哈希
    - 网络诊断：端口检测、HTTP 请求、DNS 解析、IP 查询
    - 时间工具：时区转换、时间戳、Cron 解析、日历
    - 正则调试：匹配测试、捕获组、替换预览
    - 文件处理：批量重命名、编码转换、分割合并
    - Git 助手：提交统计、变更日志、分支清理
    - 代码生成：JSON→多语言类型、SQL→ORM、OpenAPI→客户端
    - 数据库：MySQL/PostgreSQL/SQLite 查询、Shell、导出
    - API 测试：HTTP 请求、Collection、断言、性能测试
    - 系统监控：TUI 仪表盘、CPU/内存/磁盘/网络/进程
    
    常用示例:
      devkit json format data.json
      devkit crypto jwt decode eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...
      devkit net portcheck 8080
      devkit codec base64 encode -c "hello world"
    """
    ctx.ensure_object(dict)
    ctx.obj['no_color'] = no_color
    ctx.obj['config_path'] = config
    
    if no_color:
        import colorama
        colorama.deinit()
        click.echo = click.secho = lambda *args, **kwargs: print(*args, file=kwargs.get('file', sys.stdout))


@cli.group()
@click.pass_context
def config(ctx):
    """配置管理
    
    管理 devkit 的所有配置项，包括编辑器、时区、服务器连接等。
    """
    pass


@config.command('show')
@click.option('--key', '-k', help='显示指定的配置键值')
def config_show(key):
    """显示当前配置
    
    示例:
      devkit config show
      devkit config show --key general.default_editor
    """
    cfg = get_config()
    
    if key:
        value = cfg.get(key)
        if value is not None:
            cprint(f'{key}: ', Color.CYAN, nl=False)
            if isinstance(value, (dict, list)):
                import json
                click.echo(json.dumps(value, indent=2, ensure_ascii=False))
            else:
                cprint(str(value), Color.GREEN)
        else:
            cprint(f'Key not found: {key}', Color.YELLOW)
    else:
        cprint(f'Config file: {cfg.config_path}', Color.CYAN, bold=True)
        import json
        for k, v in cfg.config.items():
            cprint(f'{k}: ', Color.CYAN, nl=False)
            if isinstance(v, (dict, list)):
                click.echo(json.dumps(v, indent=2, ensure_ascii=False))
            else:
                cprint(str(v), Color.GREEN)


@config.command('set')
@click.argument('key')
@click.argument('value')
def config_set(key, value):
    """设置配置项
    
    示例:
      devkit config set general.default_editor code
      devkit config set general.timezone America/New_York
    """
    cfg = get_config()
    
    if value.lower() == 'true':
        value = True
    elif value.lower() == 'false':
        value = False
    elif value.isdigit():
        value = int(value)
    elif value.replace('.', '', 1).isdigit():
        value = float(value)
    
    cfg.set(key, value)
    cprint(f'Set {key} = {value}', Color.GREEN)


@config.command('delete')
@click.argument('key')
def config_delete(key):
    """Delete a configuration value"""
    cfg = get_config()
    cfg.delete(key)
    cprint(f'Deleted {key}', Color.GREEN)


@config.command('add-server')
@click.argument('name')
@click.argument('host')
@click.option('--port', '-p', default=22, show_default=True, help='SSH port')
@click.option('--user', '-u', help='SSH username')
def config_add_server(name, host, port, user):
    """Add a server to configuration"""
    cfg = get_config()
    cfg.add_server(name, host, port, user)
    cprint(f'Added server: {name} ({user + "@" if user else ""}{host}:{port})', Color.GREEN)


@config.command('set-token')
@click.argument('service')
@click.argument('token')
def config_set_token(service, token):
    """Set an API token for a service"""
    cfg = get_config()
    cfg.set_api_token(service, token)
    cprint(f'Set token for {service}', Color.GREEN)


@config.command('init')
@click.option('--use-keyring/--no-keyring', default=True, help='Use system keyring for master key')
def config_init(use_keyring):
    """初始化 devkit 配置
    
    引导用户完成初始配置：
    - 设置主密钥（用于加密敏感配置）
    - 选择默认编辑器
    - 设置时区
    - 添加常用服务器连接
    
    示例：
      devkit config init
      devkit config init --no-keyring
    """
    from devkit.core.config import generate_master_key, store_master_key, KEYRING_AVAILABLE
    
    cprint('=== DevKit 初始化配置向导', Color.CYAN, bold=True)
    click.echo()
    
    cfg = get_config()
    
    if not cfg.has_master_key():
        cprint('🔐 设置主密钥', Color.CYAN, bold=True)
        use_keyring = use_keyring and KEYRING_AVAILABLE
        
        if use_keyring:
            click.echo('系统 keyring 可用，主密钥将被安全存储。')
            if click.confirm('是否生成并存储主密钥到系统 keyring？', default=True):
                master_key = generate_master_key()
                if store_master_key(master_key):
                    cfg.set_master_key(master_key, persist=False)
                    cprint('✅ 主密钥已生成并存储到系统 keyring', Color.GREEN)
                else:
                    cprint('⚠️  无法存储到 keyring，使用环境变量方式', Color.YELLOW)
                    click.echo(f'请设置环境变量：export DEVKIT_MASTER_KEY={master_key.decode()}')
                    cfg.set_master_key(master_key, persist=False)
            else:
                master_key = generate_master_key()
                cfg.set_master_key(master_key, persist=False)
                cprint(f'请设置环境变量：export DEVKIT_MASTER_KEY={master_key.decode()}', Color.YELLOW)
        else:
            master_key = generate_master_key()
            cfg.set_master_key(master_key, persist=False)
            cprint(f'请设置环境变量：export DEVKIT_MASTER_KEY={master_key.decode()}', Color.YELLOW)
        click.echo()
    else:
        cprint('✅ 主密钥已配置', Color.GREEN)
        click.echo()
    
    cprint('⚙️  通用配置', Color.CYAN, bold=True)
    
    editors = ['vim', 'nvim', 'emacs', 'code', 'subl', 'nano', 'micro']
    current_editor = cfg.get('general.default_editor', 'vim')
    
    click.echo(f'当前默认编辑器: {current_editor}')
    if click.confirm('是否修改默认编辑器？', default=False):
        for i, ed in enumerate(editors, 1):
            click.echo(f'  {i}. {ed}')
        choice = click.prompt('请选择编辑器 (输入数字或自定义)', type=str, default='1')
        if choice.isdigit() and 1 <= int(choice) <= len(editors):
            editor = editors[int(choice) - 1]
        else:
            editor = choice
        cfg.set('general.default_editor', editor)
        cprint(f'✅ 已设置默认编辑器: {editor}', Color.GREEN)
    click.echo()
    
    timezones = ['Asia/Shanghai', 'Asia/Tokyo', 'America/New_York', 'Europe/London', 'UTC']
    current_tz = cfg.get('general.timezone', 'Asia/Shanghai')
    
    click.echo(f'当前时区: {current_tz}')
    if click.confirm('是否修改时区？', default=False):
        for i, tz in enumerate(timezones, 1):
            click.echo(f'  {i}. {tz}')
        choice = click.prompt('请选择时区 (输入数字或自定义)', type=str, default='1')
        if choice.isdigit() and 1 <= int(choice) <= len(timezones):
            timezone = timezones[int(choice) - 1]
        else:
            timezone = choice
        cfg.set('general.timezone', timezone)
        cprint(f'✅ 已设置时区: {timezone}', Color.GREEN)
    click.echo()
    
    cprint('🖥️  服务器连接', Color.CYAN, bold=True)
    if click.confirm('是否添加常用服务器连接？', default=False):
        while True:
            name = click.prompt('服务器名称 (输入空值退出)', default='', show_default=False)
            if not name:
                break
            host = click.prompt('主机地址')
            port = click.prompt('SSH 端口', type=int, default=22)
            user = click.prompt('用户名', default='')
            
            password = None
            if click.confirm('是否保存密码？', default=False):
                password = click.prompt('密码', hide_input=True, confirmation_prompt=True)
            
            cfg.add_server(name, host, port, user or None, password)
            cprint(f'✅ 已添加服务器: {name}', Color.GREEN)
            
            if not click.confirm('继续添加？', default=False):
                break
    click.echo()
    
    cprint('✅ 配置完成！配置文件位于:', Color.GREEN)
    click.echo(f'  {cfg.config_path}')
    click.echo()
    cprint('运行 devkit config show 查看完整配置', Color.CYAN)


cli.add_command(json)
cli.add_command(codec)
cli.add_command(crypto)
cli.add_command(net)
cli.add_command(time)
cli.add_command(regex)
cli.add_command(file)
cli.add_command(git)
cli.add_command(codegen)
cli.add_command(db)
cli.add_command(api)
cli.add_command(sysmon)


def main():
    try:
        cli(obj={})
    except KeyboardInterrupt:
        cprint('\nInterrupted by user', Color.YELLOW)
        sys.exit(130)
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)
        import traceback
        import os
        if os.environ.get('DEVKIT_DEBUG'):
            traceback.print_exc()
        sys.exit(1)


if __name__ == '__main__':
    main()
