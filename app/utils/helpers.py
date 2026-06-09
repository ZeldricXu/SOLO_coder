from datetime import datetime, timezone
import random
import string
from typing import Optional
from uuid import uuid4

from app.utils.constants import (
    INVENTORY_CODE_PREFIX,
    WAREHOUSE_CODE_PREFIX,
    ZONE_CODE_PREFIX,
    SUPPLIER_CODE_PREFIX,
    TRANSACTION_CODE_PREFIX,
    SYNC_CODE_PREFIX,
)


def generate_code(prefix: str, length: int = 8) -> str:
    timestamp = datetime.utcnow().strftime("%Y%m%d%H%M%S")
    random_chars = "".join(random.choices(string.ascii_uppercase + string.digits, k=length))
    return f"{prefix}-{timestamp}-{random_chars}"


def generate_warehouse_code() -> str:
    return generate_code(WAREHOUSE_CODE_PREFIX, 6)


def generate_zone_code() -> str:
    return generate_code(ZONE_CODE_PREFIX, 6)


def generate_inventory_code() -> str:
    return generate_code(INVENTORY_CODE_PREFIX, 6)


def generate_supplier_code() -> str:
    return generate_code(SUPPLIER_CODE_PREFIX, 6)


def generate_transaction_code() -> str:
    return generate_code(TRANSACTION_CODE_PREFIX, 6)


def generate_sync_code() -> str:
    return generate_code(SYNC_CODE_PREFIX, 6)


def generate_batch_code() -> str:
    timestamp = datetime.utcnow().strftime("%Y%m%d")
    random_chars = "".join(random.choices(string.ascii_uppercase + string.digits, k=8))
    return f"BATCH-{timestamp}-{random_chars}"


def generate_uuid() -> str:
    return str(uuid4())


def get_current_utc_time() -> datetime:
    return datetime.utcnow().replace(tzinfo=timezone.utc)


def format_datetime(dt: datetime, fmt: str = "%Y-%m-%d %H:%M:%S") -> str:
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.strftime(fmt)


def parse_datetime(date_str: str, fmt: str = "%Y-%m-%d %H:%M:%S") -> datetime:
    dt = datetime.strptime(date_str, fmt)
    return dt.replace(tzinfo=timezone.utc)


def calculate_seconds_between(start: datetime, end: Optional[datetime] = None) -> int:
    if end is None:
        end = get_current_utc_time()
    if start.tzinfo is None:
        start = start.replace(tzinfo=timezone.utc)
    if end.tzinfo is None:
        end = end.replace(tzinfo=timezone.utc)
    return int((end - start).total_seconds())


def calculate_utilization_rate(current: int, capacity: int) -> Optional[float]:
    if capacity <= 0:
        return None
    return round(current / capacity, 4)


def calculate_available_quantity(quantity: int, reserved: int, allocated: int) -> int:
    return max(0, quantity - reserved - allocated)


def calculate_total_value(quantity: int, unit_cost: float) -> float:
    return round(quantity * unit_cost, 2)


def mask_sensitive_data(data: str, start: int = 3, end: int = 3) -> str:
    if len(data) <= start + end:
        return "*" * len(data)
    return data[:start] + "*" * (len(data) - start - end) + data[-end:]


def safe_int(value: Optional[str], default: int = 0) -> int:
    try:
        return int(value) if value is not None else default
    except (ValueError, TypeError):
        return default


def safe_float(value: Optional[str], default: float = 0.0) -> float:
    try:
        return float(value) if value is not None else default
    except (ValueError, TypeError):
        return default


def truncate_string(value: str, max_length: int, suffix: str = "...") -> str:
    if len(value) <= max_length:
        return value
    return value[: max_length - len(suffix)] + suffix


def generate_transaction_reference(reference_type: str, reference_id: int) -> str:
    return f"{reference_type.upper()}-{reference_id}"


def is_valid_email(email: str) -> bool:
    import re
    pattern = r"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$"
    return re.match(pattern, email) is not None


def is_valid_phone(phone: str) -> bool:
    import re
    pattern = r"^[\d\-\s\+\(\)]{7,20}$"
    return re.match(pattern, phone) is not None


def sanitize_string(value: str, max_length: Optional[int] = None) -> str:
    value = value.strip()
    if max_length and len(value) > max_length:
        value = value[:max_length]
    return value


def dict_diff(old: dict, new: dict) -> dict:
    changes = {}
    all_keys = set(old.keys()) | set(new.keys())
    for key in all_keys:
        old_val = old.get(key)
        new_val = new.get(key)
        if old_val != new_val:
            changes[key] = {"old": old_val, "new": new_val}
    return changes


def round_to_precision(value: float, precision: int = 2) -> float:
    return round(value, precision)
