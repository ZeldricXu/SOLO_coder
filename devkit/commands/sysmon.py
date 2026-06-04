import json as json_module
import os
import sys
import time
from datetime import datetime
from pathlib import Path

import click
from pygments import highlight
from pygments.lexers import JsonLexer
from pygments.formatters import TerminalFormatter

from ..core import Color, cprint


RICH_AVAILABLE = False
PSUTIL_AVAILABLE = False

try:
    from rich.console import Console
    from rich.live import Live
    from rich.table import Table
    from rich.panel import Panel
    from rich.text import Text
    from rich.progress import BarColumn
    from rich import box
    RICH_AVAILABLE = True
except ImportError:
    pass

try:
    import psutil
    PSUTIL_AVAILABLE = True
except ImportError:
    pass


def sizeof_fmt(num, suffix='B'):
    """Format bytes to human readable"""
    for unit in ('', 'K', 'M', 'G', 'T', 'P', 'E', 'Z'):
        if abs(num) < 1024.0:
            return f'{num:.1f} {unit}{suffix}'
        num /= 1024.0
    return f'{num:.1f} Y{suffix}'


def get_cpu_info():
    """Get CPU usage info"""
    if not PSUTIL_AVAILABLE:
        return None
    
    cpu_percent = psutil.cpu_percent(interval=None, percpu=True)
    cpu_freq = psutil.cpu_freq()
    
    return {
        'per_core': cpu_percent,
        'avg': sum(cpu_percent) / len(cpu_percent) if cpu_percent else 0,
        'freq_current': cpu_freq.current if cpu_freq else 0,
        'freq_max': cpu_freq.max if cpu_freq else 0,
    }


def get_memory_info():
    """Get memory usage info"""
    if not PSUTIL_AVAILABLE:
        return None
    
    mem = psutil.virtual_memory()
    swap = psutil.swap_memory()
    
    return {
        'total': mem.total,
        'used': mem.used,
        'available': mem.available,
        'percent': mem.percent,
        'buffers': getattr(mem, 'buffers', 0),
        'cached': getattr(mem, 'cached', 0),
        'swap_total': swap.total,
        'swap_used': swap.used,
        'swap_percent': swap.percent,
    }


def get_disk_info():
    """Get disk usage info"""
    if not PSUTIL_AVAILABLE:
        return None
    
    disks = []
    for part in psutil.disk_partitions():
        try:
            usage = psutil.disk_usage(part.mountpoint)
            disks.append({
                'device': part.device,
                'mountpoint': part.mountpoint,
                'fstype': part.fstype,
                'total': usage.total,
                'used': usage.used,
                'free': usage.free,
                'percent': usage.percent,
            })
        except (PermissionError, OSError):
            pass
    
    io_counters = psutil.disk_io_counters()
    
    return {
        'partitions': disks,
        'read_bytes': io_counters.read_bytes if io_counters else 0,
        'write_bytes': io_counters.write_bytes if io_counters else 0,
    }


def get_network_info():
    """Get network usage info"""
    if not PSUTIL_AVAILABLE:
        return None
    
    interfaces = []
    io_counters = psutil.net_io_counters(pernic=True)
    
    for iface, addrs in psutil.net_if_addrs().items():
        if iface.startswith('lo') or iface.startswith('docker'):
            continue
        
        ipv4 = None
        for addr in addrs:
            if addr.family == 2:
                ipv4 = addr.address
                break
        
        if iface in io_counters:
            io = io_counters[iface]
            interfaces.append({
                'name': iface,
                'ipv4': ipv4,
                'bytes_sent': io.bytes_sent,
                'bytes_recv': io.bytes_recv,
                'packets_sent': io.packets_sent,
                'packets_recv': io.packets_recv,
                'errors_in': io.errin,
                'errors_out': io.errout,
            })
    
    return {
        'interfaces': interfaces,
    }


def get_process_info():
    """Get top processes by CPU"""
    if not PSUTIL_AVAILABLE:
        return None
    
    processes = []
    for proc in psutil.process_iter(['pid', 'name', 'cpu_percent', 'memory_percent', 'username']):
        try:
            processes.append({
                'pid': proc.info['pid'],
                'name': proc.info['name'],
                'cpu_percent': proc.info['cpu_percent'],
                'memory_percent': proc.info['memory_percent'],
                'username': proc.info['username'],
            })
        except (psutil.NoSuchProcess, psutil.AccessDenied):
            pass
    
    processes.sort(key=lambda x: x['cpu_percent'], reverse=True)
    return processes[:10]


def make_bar(percent, width=20):
    """Create ASCII progress bar"""
    filled = int(width * percent / 100)
    return '█' * filled + '░' * (width - filled)


def get_color(percent):
    """Get color based on percent"""
    if percent < 50:
        return 'green'
    elif percent < 75:
        return 'yellow'
    else:
        return 'red'


def build_dashboard(prev_net=None, prev_disk=None):
    """Build the dashboard display"""
    if not RICH_AVAILABLE:
        return None
    
    console = Console()
    width = console.size.width
    
    cpu_info = get_cpu_info()
    mem_info = get_memory_info()
    disk_info = get_disk_info()
    net_info = get_network_info()
    proc_info = get_process_info()
    
    root = Table.grid(expand=True)
    root.add_column()
    
    title = Table.grid(expand=True)
    title.add_column(justify='center')
    title.add_row(Text(f'📊 System Monitor - {datetime.now().strftime("%Y-%m-%d %H:%M:%S")}', 
                       style='bold cyan', overflow='fold'))
    root.add_row(Panel(title, border_style='cyan'))
    
    main_grid = Table.grid(expand=True)
    main_grid.add_column(ratio=1)
    main_grid.add_column(ratio=1)
    
    left_grid = Table.grid(expand=True)
    left_grid.add_column()
    
    if cpu_info:
        cpu_table = Table(title='CPU Usage', show_header=False, box=box.SIMPLE, expand=True)
        cpu_table.add_column('Core', justify='right', style='cyan')
        cpu_table.add_column('Usage', ratio=1)
        cpu_table.add_column('%', justify='right', style='bold')
        
        for i, pct in enumerate(cpu_info['per_core']):
            color = get_color(pct)
            bar = make_bar(pct, width=max(15, width // 6))
            cpu_table.add_row(f'Core {i}', f'[{color}]{bar}[/{color}]', f'[{color}]{pct:5.1f}%[/{color}]')
        
        avg_color = get_color(cpu_info['avg'])
        cpu_table.add_row(f'Avg', f'[{avg_color}]{make_bar(cpu_info["avg"], width=max(15, width // 6))}[/{avg_color}]', 
                          f'[{avg_color}]{cpu_info["avg"]:5.1f}%[/{avg_color}]')
        
        left_grid.add_row(Panel(cpu_table, border_style='blue'))
    
    if mem_info:
        mem_table = Table(title='Memory Usage', show_header=False, box=box.SIMPLE, expand=True)
        mem_table.add_column('Item', style='cyan')
        mem_table.add_column('Used', justify='right')
        mem_table.add_column('Total', justify='right')
        mem_table.add_column('', ratio=1)
        mem_table.add_column('%', justify='right', style='bold')
        
        mem_color = get_color(mem_info['percent'])
        mem_table.add_row(
            'RAM', 
            sizeof_fmt(mem_info['used']), 
            sizeof_fmt(mem_info['total']), 
            f'[{mem_color}]{make_bar(mem_info["percent"], width=max(15, width // 6))}[/{mem_color}]',
            f'[{mem_color}]{mem_info["percent"]:5.1f}%[/{mem_color}]',
        )
        
        swap_color = get_color(mem_info['swap_percent'])
        mem_table.add_row(
            'Swap', 
            sizeof_fmt(mem_info['swap_used']), 
            sizeof_fmt(mem_info['swap_total']), 
            f'[{swap_color}]{make_bar(mem_info["swap_percent"], width=max(15, width // 6))}[/{swap_color}]',
            f'[{swap_color}]{mem_info["swap_percent"]:5.1f}%[/{swap_color}]',
        )
        
        mem_table.add_row('Available', '', sizeof_fmt(mem_info['available']), '', '')
        
        left_grid.add_row(Panel(mem_table, border_style='green'))
    
    right_grid = Table.grid(expand=True)
    right_grid.add_column()
    
    if disk_info:
        disk_table = Table(title='Disk Usage', show_header=True, box=box.SIMPLE, expand=True)
        disk_table.add_column('Mount', style='cyan')
        disk_table.add_column('Used', justify='right')
        disk_table.add_column('Total', justify='right')
        disk_table.add_column('', ratio=1)
        disk_table.add_column('%', justify='right', style='bold')
        
        for d in disk_info['partitions'][:5]:
            color = get_color(d['percent'])
            disk_table.add_row(
                d['mountpoint'],
                sizeof_fmt(d['used']),
                sizeof_fmt(d['total']),
                f'[{color}]{make_bar(d["percent"], width=max(15, width // 6))}[/{color}]',
                f'[{color}]{d["percent"]:5.1f}%[/{color}]',
            )
        
        right_grid.add_row(Panel(disk_table, border_style='yellow'))
    
    if net_info:
        net_table = Table(title='Network Interfaces', show_header=True, box=box.SIMPLE, expand=True)
        net_table.add_column('Iface', style='cyan')
        net_table.add_column('IP', style='green')
        net_table.add_column('↓ Speed', justify='right', style='blue')
        net_table.add_column('↑ Speed', justify='right', style='magenta')
        net_table.add_column('↓ Total', justify='right')
        net_table.add_column('↑ Total', justify='right')
        
        now = time.time()
        for iface in net_info['interfaces'][:5]:
            rx_speed = tx_speed = '0 B/s'
            
            if prev_net and prev_net['data'] and iface['name'] in prev_net['data']:
                prev = prev_net['data'][iface['name']]
                dt = now - prev_net['time']
                if dt > 0:
                    rx_speed = sizeof_fmt((iface['bytes_recv'] - prev['bytes_recv']) / dt) + '/s'
                    tx_speed = sizeof_fmt((iface['bytes_sent'] - prev['bytes_sent']) / dt) + '/s'
            
            net_table.add_row(
                iface['name'],
                iface['ipv4'] or '-',
                rx_speed,
                tx_speed,
                sizeof_fmt(iface['bytes_recv']),
                sizeof_fmt(iface['bytes_sent']),
            )
        
        right_grid.add_row(Panel(net_table, border_style='magenta'))
    
    main_grid.add_row(left_grid, right_grid)
    root.add_row(main_grid)
    
    if proc_info:
        proc_table = Table(title='Top 10 Processes (by CPU)', show_header=True, box=box.SIMPLE, expand=True)
        proc_table.add_column('PID', justify='right', style='cyan')
        proc_table.add_column('Name', style='bold')
        proc_table.add_column('User', style='green')
        proc_table.add_column('CPU %', justify='right', style='bold')
        proc_table.add_column('Memory %', justify='right', style='bold')
        
        for p in proc_info:
            cpu_color = get_color(p['cpu_percent'])
            mem_color = get_color(p['memory_percent'])
            proc_table.add_row(
                str(p['pid']),
                p['name'][:30],
                p['username'] or '-',
                f'[{cpu_color}]{p["cpu_percent"]:6.1f}%[/{cpu_color}]',
                f'[{mem_color}]{p["memory_percent"]:6.1f}%[/{mem_color}]',
            )
        
        root.add_row(Panel(proc_table, border_style='red'))
    
    footer = Table.grid(expand=True)
    footer.add_column(justify='center')
    footer.add_row(Text('Press Ctrl+C to exit | [dim]Refresh interval: 1s[/dim]', 
                        style='dim', overflow='fold'))
    root.add_row(footer)
    
    net_snapshot = {iface['name']: {'bytes_sent': iface['bytes_sent'], 'bytes_recv': iface['bytes_recv']}
                    for iface in net_info['interfaces']} if net_info else {}
    
    return root, {'time': now, 'data': net_snapshot}


def log_to_file(interval_seconds, output_dir):
    """Log system metrics to files"""
    if not PSUTIL_AVAILABLE:
        cprint('psutil is required. Install: pip install psutil', Color.RED)
        return
    
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    
    cprint(f'Logging system metrics every {interval_seconds}s to {output_path}', Color.GREEN)
    cprint('Press Ctrl+C to stop', Color.YELLOW)
    
    timestamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    cpu_file = output_path / f'cpu_{timestamp}.json'
    mem_file = output_path / f'memory_{timestamp}.json'
    disk_file = output_path / f'disk_{timestamp}.json'
    net_file = output_path / f'network_{timestamp}.json'
    proc_file = output_path / f'processes_{timestamp}.json'
    
    try:
        while True:
            now = datetime.now().isoformat()
            
            cpu_info = get_cpu_info()
            with open(cpu_file, 'a') as f:
                f.write(json_module.dumps({'timestamp': now, **cpu_info}) + '\n')
            
            mem_info = get_memory_info()
            with open(mem_file, 'a') as f:
                f.write(json_module.dumps({'timestamp': now, **mem_info}) + '\n')
            
            disk_info = get_disk_info()
            with open(disk_file, 'a') as f:
                f.write(json_module.dumps({'timestamp': now, **disk_info}) + '\n')
            
            net_info = get_network_info()
            with open(net_file, 'a') as f:
                f.write(json_module.dumps({'timestamp': now, **net_info}) + '\n')
            
            proc_info = get_process_info()
            with open(proc_file, 'a') as f:
                f.write(json_module.dumps({'timestamp': now, 'processes': proc_info}) + '\n')
            
            time.sleep(interval_seconds)
    
    except KeyboardInterrupt:
        cprint('\nLogging stopped', Color.YELLOW)
        cprint(f'Files saved to {output_path}', Color.GREEN)


def simple_monitor():
    """Simple monitor without rich library"""
    if not PSUTIL_AVAILABLE:
        cprint('psutil is required. Install: pip install psutil', Color.RED)
        return
    
    cprint('System Monitor (simple mode)', Color.CYAN, bold=True)
    cprint('Press Ctrl+C to exit', Color.YELLOW)
    
    try:
        while True:
            cpu_info = get_cpu_info()
            mem_info = get_memory_info()
            
            lines = [f'\n=== {datetime.now().strftime("%Y-%m-%d %H:%M:%S")} ===']
            
            if cpu_info:
                core_bars = ' '.join(
                    f'{i}:{make_bar(pct, width=10)} {pct:5.1f}%'
                    for i, pct in enumerate(cpu_info['per_core'][:8])
                )
                lines.append(f'CPU:  AVG {cpu_info["avg"]:5.1f}%')
                lines.append(core_bars)
            
            if mem_info:
                lines.append(f'MEM:  {sizeof_fmt(mem_info["used"])} / {sizeof_fmt(mem_info["total"])} ({mem_info["percent"]:.1f}%)')
                lines.append(f'      Bar: {make_bar(mem_info["percent"], width=40)}')
            
            disk_info = get_disk_info()
            if disk_info:
                lines.append(f'DISK:')
                for d in disk_info['partitions'][:3]:
                    lines.append(f'  {d["mountpoint"]:20s} {sizeof_fmt(d["used"]):>10s} / {sizeof_fmt(d["total"]):>10s} ({d["percent"]:5.1f}%) {make_bar(d["percent"], width=20)}')
            
            proc_info = get_process_info()
            if proc_info:
                lines.append(f'PROCESSES (Top 5):')
                for p in proc_info[:5]:
                    lines.append(f'  {p["pid"]:>6d} {p["name"][:20]:<20s} CPU: {p["cpu_percent"]:6.1f}% MEM: {p["memory_percent"]:6.1f}%')
            
            click.echo('\033c', nl=False)
            click.echo('\n'.join(lines))
            time.sleep(1)
    
    except KeyboardInterrupt:
        click.echo()
        cprint('Exited', Color.YELLOW)


@click.group()
def sysmon():
    """System monitoring dashboard"""
    if not PSUTIL_AVAILABLE:
        cprint('Warning: psutil not installed. Install with: pip install psutil', Color.YELLOW)


@sysmon.command('tui')
def sysmon_tui():
    """Start TUI system monitor (requires rich)
    
    Examples:
      devkit sysmon tui
    """
    if not PSUTIL_AVAILABLE:
        cprint('psutil is required. Install: pip install psutil', Color.RED)
        return
    
    if not RICH_AVAILABLE:
        cprint('rich library not available. Install with: pip install rich', Color.YELLOW)
        cprint('Falling back to simple mode...', Color.YELLOW)
        simple_monitor()
        return
    
    try:
        prev_net = None
        
        with Live(refresh_per_second=1, screen=True) as live:
            while True:
                dashboard, new_net = build_dashboard(prev_net)
                if dashboard:
                    live.update(dashboard)
                prev_net = new_net
                time.sleep(1)
    except KeyboardInterrupt:
        cprint('\nExited', Color.YELLOW)


@sysmon.command('log')
@click.argument('interval_seconds', type=int, default=5)
@click.argument('output_dir', type=click.Path(), default='./sysmon_logs')
def sysmon_log(interval_seconds, output_dir):
    """Log system metrics to JSON files
    
    Examples:
      devkit sysmon log 5 ./logs
      devkit sysmon log 10 /var/log/sysmon
    """
    log_to_file(interval_seconds, output_dir)


@sysmon.command('once')
@click.option('--output', '-o', type=click.Path(), help='Output to file')
def sysmon_once(output):
    """Get one-time system snapshot
    
    Examples:
      devkit sysmon once
      devkit sysmon once -o snapshot.json
    """
    if not PSUTIL_AVAILABLE:
        cprint('psutil is required. Install: pip install psutil', Color.RED)
        return
    
    cpu_info = get_cpu_info()
    mem_info = get_memory_info()
    disk_info = get_disk_info()
    net_info = get_network_info()
    proc_info = get_process_info()
    
    snapshot = {
        'timestamp': datetime.now().isoformat(),
        'cpu': cpu_info,
        'memory': mem_info,
        'disk': disk_info,
        'network': net_info,
        'top_processes': proc_info,
    }
    
    output_content = json_module.dumps(snapshot, indent=2, ensure_ascii=False)
    
    if output:
        with open(output, 'w', encoding='utf-8') as f:
            f.write(output_content)
        cprint(f'Snapshot saved to {output}', Color.GREEN)
    else:
        if RICH_AVAILABLE:
            try:
                colored = highlight(output_content, JsonLexer(), TerminalFormatter())
                click.echo(colored)
            except Exception:
                click.echo(output_content)
        else:
            click.echo(output_content)


@sysmon.command('check')
def sysmon_check():
    """Check available system monitoring features
    
    Examples:
      devkit sysmon check
    """
    cprint('System Monitor Features:', Color.CYAN, bold=True)
    
    if PSUTIL_AVAILABLE:
        cprint('  ✓ psutil available', Color.GREEN)
        cprint(f'    Version: {psutil.__version__}', Color.CYAN)
        cprint(f'    CPU cores: {psutil.cpu_count(logical=False)} physical, {psutil.cpu_count()} logical', Color.CYAN)
    else:
        cprint('  ✗ psutil not available', Color.RED)
        cprint('    Install: pip install psutil', Color.YELLOW)
    
    if RICH_AVAILABLE:
        cprint('  ✓ rich available', Color.GREEN)
        cprint(f'    Version: {Console()._console.__class__.__module__.split(".")[0] if False else "installed"}', Color.CYAN)
    else:
        cprint('  ✗ rich not available (TUI disabled)', Color.YELLOW)
        cprint('    Install: pip install rich', Color.YELLOW)
