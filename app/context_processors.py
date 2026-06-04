import json
from datetime import datetime, timedelta


def get_status_color(status):
    colors = {
        "healthy": "#22c55e",
        "normal": "#22c55e",
        "warning": "#f59e0b",
        "degraded": "#f59e0b",
        "critical": "#ef4444",
        "down": "#ef4444",
        "firing": "#ef4444",
        "acknowledged": "#f59e0b",
        "resolved": "#22c55e",
        "unknown": "#6b7280",
    }
    return colors.get(status, "#6b7280")


def get_status_bg_color(status):
    colors = {
        "healthy": "bg-green-500/20",
        "normal": "bg-green-500/20",
        "warning": "bg-yellow-500/20",
        "degraded": "bg-yellow-500/20",
        "critical": "bg-red-500/20",
        "down": "bg-red-500/20",
        "firing": "bg-red-500/20",
        "acknowledged": "bg-yellow-500/20",
        "resolved": "bg-green-500/20",
        "unknown": "bg-gray-500/20",
    }
    return colors.get(status, "bg-gray-500/20")


def get_level_color(level):
    colors = {
        "P0": "#ef4444",
        "P1": "#f97316",
        "P2": "#f59e0b",
        "P3": "#3b82f6",
    }
    return colors.get(level, "#6b7280")


def get_level_bg_color(level):
    colors = {
        "P0": "bg-red-500/20 border-red-500",
        "P1": "bg-orange-500/20 border-orange-500",
        "P2": "bg-yellow-500/20 border-yellow-500",
        "P3": "bg-blue-500/20 border-blue-500",
    }
    return colors.get(level, "bg-gray-500/20 border-gray-500")


def format_duration(ms):
    if ms is None:
        return "-"
    if ms < 1000:
        return f"{ms:.0f}ms"
    return f"{ms/1000:.2f}s"


def format_number(num):
    if num is None:
        return "-"
    if isinstance(num, float):
        return f"{num:,.2f}"
    return f"{num:,}"


def format_datetime(dt):
    if dt is None:
        return "-"
    return dt.strftime("%Y-%m-%d %H:%M:%S")


def format_date(dt):
    if dt is None:
        return "-"
    if isinstance(dt, datetime):
        return dt.strftime("%Y-%m-%d")
    return dt.strftime("%Y-%m-%d")


def fromjson(value):
    if value is None:
        return {}
    if isinstance(value, str):
        try:
            return json.loads(value)
        except (json.JSONDecodeError, TypeError):
            return {}
    return value


def tojson(value):
    if value is None:
        return "{}"
    return json.dumps(value, ensure_ascii=False)


def register_helpers(templates):
    templates.env.globals["get_status_color"] = get_status_color
    templates.env.globals["get_status_bg_color"] = get_status_bg_color
    templates.env.globals["get_level_color"] = get_level_color
    templates.env.globals["get_level_bg_color"] = get_level_bg_color
    templates.env.globals["format_duration"] = format_duration
    templates.env.globals["format_number"] = format_number
    templates.env.globals["format_datetime"] = format_datetime
    templates.env.globals["format_date"] = format_date
    templates.env.filters["fromjson"] = fromjson
    templates.env.filters["tojson"] = tojson


def init_app(app):
    pass
