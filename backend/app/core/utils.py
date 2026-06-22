from datetime import datetime, date, timedelta
from typing import Tuple, Optional


def get_week_range(target_date: Optional[date] = None) -> Tuple[date, date]:
    if target_date is None:
        target_date = date.today()
    monday = target_date - timedelta(days=target_date.weekday())
    friday = monday + timedelta(days=4)
    return monday, friday


def get_week_key(target_date: Optional[date] = None) -> str:
    monday, _ = get_week_range(target_date)
    return monday.strftime("%Y-W%W")


def get_week_display(target_date: Optional[date] = None) -> str:
    monday, friday = get_week_range(target_date)
    return f"{monday.strftime('%Y年%m月%d日')} - {friday.strftime('%m月%d日')}"


def is_within_deadline(deadline: datetime, now: Optional[datetime] = None) -> bool:
    if now is None:
        now = datetime.now()
    return now <= deadline


def get_prev_week_range(target_date: Optional[date] = None) -> Tuple[date, date]:
    if target_date is None:
        target_date = date.today()
    prev_monday = target_date - timedelta(days=target_date.weekday() + 7)
    prev_friday = prev_monday + timedelta(days=4)
    return prev_monday, prev_friday


def get_prev_week_key(target_date: Optional[date] = None) -> str:
    prev_monday, _ = get_prev_week_range(target_date)
    return prev_monday.strftime("%Y-W%W")
