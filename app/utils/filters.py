import json
from datetime import datetime
from markupsafe import Markup


def register_filters(app):
    @app.template_filter('to_json')
    def to_json_filter(value):
        return Markup(json.dumps(value, default=str, ensure_ascii=False))

    @app.template_filter('from_json')
    def from_json_filter(value):
        if isinstance(value, str):
            try:
                return json.loads(value)
            except json.JSONDecodeError:
                return {}
        return value or {}

    @app.template_filter('format_date')
    def format_date_filter(value, fmt='%Y-%m-%d %H:%M:%S'):
        if isinstance(value, datetime):
            return value.strftime(fmt)
        return value

    @app.template_filter('relative_time')
    def relative_time_filter(value):
        if not isinstance(value, datetime):
            return value
        now = datetime.utcnow()
        diff = now - value
        seconds = diff.total_seconds()
        if seconds < 60:
            return '刚刚'
        elif seconds < 3600:
            return f'{int(seconds / 60)}分钟前'
        elif seconds < 86400:
            return f'{int(seconds / 3600)}小时前'
        elif seconds < 2592000:
            return f'{int(seconds / 86400)}天前'
        else:
            return value.strftime('%Y-%m-%d')

    @app.template_filter('file_size')
    def file_size_filter(value):
        if not value:
            return '0 B'
        for unit in ['B', 'KB', 'MB', 'GB']:
            if value < 1024:
                return f'{value:.1f} {unit}'
            value /= 1024
        return f'{value:.1f} TB'

    @app.template_filter('truncate')
    def truncate_filter(value, length=50):
        if not value:
            return ''
        value = str(value)
        if len(value) <= length:
            return value
        return value[:length] + '...'

    @app.template_filter('number_format')
    def number_format_filter(value):
        if value is None:
            return '-'
        try:
            if isinstance(value, float):
                return f'{value:,.2f}'
            return f'{int(value):,}'
        except (ValueError, TypeError):
            return str(value)

    @app.template_filter('percent')
    def percent_filter(value, decimals=1):
        if value is None:
            return '-'
        try:
            return f'{float(value) * 100:.{decimals}f}%'
        except (ValueError, TypeError):
            return str(value)
