import calendar
import datetime
import re
import sys
import time as time_module
from pathlib import Path

import click
import pytz
from croniter import croniter
from dateutil import parser as date_parser
from dateutil.relativedelta import relativedelta

from ..core import Color, cprint


@click.group(name='time')
def time_group():
    """Time and date utility commands"""
    pass


@time_group.command('now')
@click.option('--timezone', '-z', help='Timezone (e.g., Asia/Shanghai, UTC, America/New_York)')
@click.option('--format', '-f', help='Output format (strftime format string)')
@click.option('--timestamp', '-t', is_flag=True, help='Show Unix timestamp')
@click.option('--iso', '-i', is_flag=True, help='Show ISO 8601 format')
def time_now(timezone, format, timestamp, iso):
    """Show current time in various formats"""
    now = datetime.datetime.now()
    
    if timezone:
        try:
            tz = pytz.timezone(timezone)
            now = pytz.utc.localize(now).astimezone(tz)
        except pytz.UnknownTimeZoneError:
            cprint(f'Unknown timezone: {timezone}', Color.RED)
            return
    
    if timestamp:
        ts = now.timestamp()
        click.echo(f'{int(ts)}')
        return
    
    if iso:
        click.echo(now.isoformat())
        return
    
    if format:
        try:
            click.echo(now.strftime(format))
        except ValueError as e:
            cprint(f'Invalid format: {e}', Color.RED)
        return
    
    cprint('Current Time:', Color.CYAN, bold=True)
    cprint(f'  Local:    {now.strftime("%Y-%m-%d %H:%M:%S %Z")}', Color.GREEN)
    cprint(f'  UTC:      {datetime.datetime.utcnow().strftime("%Y-%m-%d %H:%M:%S UTC")}', Color.GREEN)
    cprint(f'  Timestamp: {int(now.timestamp())}', Color.CYAN)
    cprint(f'  ISO 8601:  {now.isoformat()}', Color.CYAN)
    cprint(f'  RFC 2822:  {now.strftime("%a, %d %b %Y %H:%M:%S %z")}', Color.CYAN)


@time_group.command('convert')
@click.argument('value')
@click.option('--from', 'from_fmt', default='auto',
              type=click.Choice(['auto', 'timestamp', 'millis', 'iso', 'date']),
              show_default=True, help='Source format')
@click.option('--to', 'to_fmt', default='all',
              type=click.Choice(['all', 'timestamp', 'millis', 'iso', 'date', 'utc']),
              show_default=True, help='Target format')
@click.option('--timezone', '-z', help='Timezone for date input/output')
def time_convert(value, from_fmt, to_fmt, timezone):
    """Convert between timestamp, ISO8601, and date formats"""
    dt = None
    tz = None
    
    if timezone:
        try:
            tz = pytz.timezone(timezone)
        except pytz.UnknownTimeZoneError:
            cprint(f'Unknown timezone: {timezone}', Color.RED)
            return
    
    if from_fmt == 'auto':
        if re.match(r'^\d{10}$', value):
            from_fmt = 'timestamp'
        elif re.match(r'^\d{13}$', value):
            from_fmt = 'millis'
        elif re.match(r'^\d{4}-\d{2}-\d{2}T', value):
            from_fmt = 'iso'
        else:
            from_fmt = 'date'
    
    try:
        if from_fmt == 'timestamp':
            dt = datetime.datetime.fromtimestamp(int(value))
        elif from_fmt == 'millis':
            dt = datetime.datetime.fromtimestamp(int(value) / 1000)
        elif from_fmt == 'iso':
            dt = datetime.datetime.fromisoformat(value.replace('Z', '+00:00'))
        elif from_fmt == 'date':
            dt = date_parser.parse(value)
    except Exception as e:
        cprint(f'Error parsing input: {e}', Color.RED)
        return
    
    if tz and dt.tzinfo is None:
        dt = tz.localize(dt)
    
    if to_fmt == 'timestamp':
        click.echo(str(int(dt.timestamp())))
    elif to_fmt == 'millis':
        click.echo(str(int(dt.timestamp() * 1000)))
    elif to_fmt == 'iso':
        click.echo(dt.isoformat())
    elif to_fmt == 'date':
        click.echo(dt.strftime('%Y-%m-%d %H:%M:%S'))
    elif to_fmt == 'utc':
        if dt.tzinfo:
            dt_utc = dt.astimezone(pytz.UTC)
            click.echo(dt_utc.strftime('%Y-%m-%d %H:%M:%S UTC'))
        else:
            click.echo(dt.strftime('%Y-%m-%d %H:%M:%S'))
    else:
        cprint(f'Input: {value} (parsed as {from_fmt})', Color.CYAN, bold=True)
        cprint(f'  Timestamp: {int(dt.timestamp())}', Color.GREEN)
        cprint(f'  Millis:    {int(dt.timestamp() * 1000)}', Color.GREEN)
        cprint(f'  ISO 8601:  {dt.isoformat()}', Color.GREEN)
        cprint(f'  Local:     {dt.strftime("%Y-%m-%d %H:%M:%S")}', Color.CYAN)
        if dt.tzinfo:
            cprint(f'  UTC:       {dt.astimezone(pytz.UTC).strftime("%Y-%m-%d %H:%M:%S UTC")}', Color.CYAN)


@time_group.command('timezone')
@click.argument('source_tz', required=False)
@click.argument('target_tz', required=False)
@click.option('--time', '-t', help='Time to convert (default: now)')
@click.option('--list', '-l', is_flag=True, help='List available timezones')
@click.option('--search', '-s', help='Search timezones by name')
def timezone_cmd(source_tz, target_tz, time, list, search):
    """Timezone conversion and listing"""
    if list or search:
        zones = pytz.all_timezones
        if search:
            zones = [z for z in zones if search.lower() in z.lower()]
        for z in zones:
            try:
                now = datetime.datetime.now(pytz.timezone(z))
                offset = now.strftime('%z')
                cprint(f'  {z:35} {offset} {now.strftime("%Y-%m-%d %H:%M")}', Color.GREEN)
            except Exception:
                pass
        return
    
    if not source_tz or not target_tz:
        cprint('Error: source and target timezones required', Color.RED)
        return
    
    try:
        src_tz = pytz.timezone(source_tz)
        tgt_tz = pytz.timezone(target_tz)
    except pytz.UnknownTimeZoneError as e:
        cprint(f'Unknown timezone: {e}', Color.RED)
        return
    
    if time:
        try:
            dt = date_parser.parse(time)
            dt = src_tz.localize(dt)
        except Exception as e:
            cprint(f'Error parsing time: {e}', Color.RED)
            return
    else:
        dt = datetime.datetime.now(src_tz)
    
    converted = dt.astimezone(tgt_tz)
    
    cprint(f'{source_tz}:', Color.CYAN, bold=True)
    cprint(f'  {dt.strftime("%Y-%m-%d %H:%M:%S %Z")}', Color.GREEN)
    cprint(f'{target_tz}:', Color.CYAN, bold=True)
    cprint(f'  {converted.strftime("%Y-%m-%d %H:%M:%S %Z")}', Color.GREEN)


@time_group.command('cron')
@click.argument('expression')
@click.option('--count', '-n', default=5, show_default=True, help='Number of next runs to show')
@click.option('--from', 'from_time', help='Start time (default: now)')
@click.option('--validate', '-v', is_flag=True, help='Validate cron expression only')
def cron_cmd(expression, count, from_time, validate):
    """Parse cron expression and show next run times"""
    try:
        if not croniter.is_valid(expression):
            cprint(f'Invalid cron expression: {expression}', Color.RED)
            return
    except Exception as e:
        cprint(f'Invalid cron expression: {e}', Color.RED)
        return
    
    if validate:
        cprint('Valid cron expression', Color.GREEN)
        return
    
    base = datetime.datetime.now()
    if from_time:
        try:
            base = date_parser.parse(from_time)
        except Exception as e:
            cprint(f'Error parsing start time: {e}', Color.RED)
            return
    
    try:
        cron = croniter(expression, base)
        
        cprint(f'Cron: {expression}', Color.CYAN, bold=True)
        cprint(f'Next {count} run times:', Color.CYAN)
        for i in range(count):
            next_run = cron.get_next(datetime.datetime)
            cprint(f'  {i+1:2}. {next_run.strftime("%Y-%m-%d %H:%M:%S")}', Color.GREEN)
        
        cprint('\nCron Field Explanation:', Color.CYAN)
        fields = expression.split()
        labels = ['Minute', 'Hour', 'Day of Month', 'Month', 'Day of Week']
        if len(fields) >= 6:
            labels.insert(0, 'Second')
        for i, (label, value) in enumerate(zip(labels, fields)):
            cprint(f'  {label:13}: {value}', Color.CYAN)
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)


@time_group.command('diff')
@click.argument('date1')
@click.argument('date2', required=False)
@click.option('--business-days', '-b', is_flag=True, help='Calculate business days only')
@click.option('--format', '-f', default='auto',
              type=click.Choice(['auto', 'days', 'hours', 'minutes', 'seconds']),
              show_default=True, help='Output format')
def time_diff(date1, date2, business_days, format):
    """Calculate difference between two dates"""
    try:
        d1 = date_parser.parse(date1)
        d2 = date_parser.parse(date2) if date2 else datetime.datetime.now()
    except Exception as e:
        cprint(f'Error parsing date: {e}', Color.RED)
        return
    
    if d2 < d1:
        d1, d2 = d2, d1
    
    delta = d2 - d1
    
    if business_days:
        bd = 0
        current = d1.date()
        end = d2.date()
        while current < end:
            if current.weekday() < 5:
                bd += 1
            current += datetime.timedelta(days=1)
        
        cprint(f'Difference between:', Color.CYAN, bold=True)
        cprint(f'  {d1.strftime("%Y-%m-%d %H:%M:%S")}', Color.GREEN)
        cprint(f'  {d2.strftime("%Y-%m-%d %H:%M:%S")}', Color.GREEN)
        cprint(f'\nBusiness days: {bd}', Color.CYAN)
        cprint(f'Calendar days: {delta.days}', Color.CYAN)
        return
    
    if format == 'days':
        click.echo(str(delta.days))
    elif format == 'hours':
        click.echo(str(delta.total_seconds() // 3600))
    elif format == 'minutes':
        click.echo(str(delta.total_seconds() // 60))
    elif format == 'seconds':
        click.echo(str(int(delta.total_seconds())))
    else:
        cprint(f'Difference between:', Color.CYAN, bold=True)
        cprint(f'  {d1.strftime("%Y-%m-%d %H:%M:%S")}', Color.GREEN)
        cprint(f'  {d2.strftime("%Y-%m-%d %H:%M:%S")}', Color.GREEN)
        cprint(f'\n  Days:         {delta.days}', Color.CYAN)
        cprint(f'  Hours:        {int(delta.total_seconds() // 3600)}', Color.CYAN)
        cprint(f'  Minutes:      {int(delta.total_seconds() // 60)}', Color.CYAN)
        cprint(f'  Seconds:      {int(delta.total_seconds())}', Color.CYAN)
        
        rd = relativedelta(d2, d1)
        cprint(f'\n  Years:        {rd.years}', Color.CYAN)
        cprint(f'  Months:       {rd.months}', Color.CYAN)
        cprint(f'  Days:         {rd.days}', Color.CYAN)


@time_group.command('countdown')
@click.argument('target')
@click.option('--message', '-m', default='Time remaining:', help='Message to display')
@click.option('--live', '-l', is_flag=True, help='Live updating countdown')
def countdown(target, message, live):
    """Countdown to a target time"""
    try:
        target_dt = date_parser.parse(target)
    except Exception as e:
        cprint(f'Error parsing target time: {e}', Color.RED)
        return
    
    if not live:
        now = datetime.datetime.now()
        delta = target_dt - now
        if delta.total_seconds() < 0:
            cprint('Target time has passed!', Color.RED)
            return
        
        days = delta.days
        hours, remainder = divmod(delta.seconds, 3600)
        minutes, seconds = divmod(remainder, 60)
        
        cprint(f'{message}', Color.CYAN, bold=True)
        cprint(f'  {days} days, {hours:02d}:{minutes:02d}:{seconds:02d}', Color.GREEN)
        return
    
    try:
        while True:
            now = datetime.datetime.now()
            delta = target_dt - now
            if delta.total_seconds() < 0:
                cprint('\nTime\'s up!', Color.GREEN, bold=True)
                break
            
            days = delta.days
            hours, remainder = divmod(delta.seconds, 3600)
            minutes, seconds = divmod(remainder, 60)
            
            sys.stdout.write(f'\r{message} {days} days, {hours:02d}:{minutes:02d}:{seconds:02d}')
            sys.stdout.flush()
            time_module.sleep(1)
    except KeyboardInterrupt:
        cprint('\nCountdown stopped', Color.YELLOW)


@time_group.command('calendar')
@click.argument('month', type=int, required=False)
@click.argument('year', type=int, required=False)
@click.option('--no-highlight', is_flag=True, help='Do not highlight today')
def calendar_cmd(month, year, no_highlight):
    """Display calendar for month/year"""
    now = datetime.datetime.now()
    y = year or now.year
    m = month or now.month
    
    try:
        cal = calendar.monthcalendar(y, m)
        month_name = calendar.month_name[m]
        
        cprint(f'{month_name} {y}'.center(20), Color.CYAN, bold=True)
        cprint('Mo Tu We Th Fr Sa Su', Color.CYAN)
        
        for week in cal:
            line = ''
            for day in week:
                if day == 0:
                    line += '   '
                else:
                    is_today = (day == now.day and m == now.month and y == now.year and not no_highlight)
                    day_str = f'{day:2d} '
                    if is_today:
                        line += Color.wrap(day_str, Color.BG_GREEN + Color.BLACK)
                    else:
                        line += day_str
            click.echo(line)
    except Exception as e:
        cprint(f'Error: {e}', Color.RED)
