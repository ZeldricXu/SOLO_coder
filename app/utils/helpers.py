from datetime import datetime, timezone, timedelta
import random
import string
from typing import Optional
from uuid import uuid4
import math

from app.utils.constants import (
    INVENTORY_CODE_PREFIX,
    WAREHOUSE_CODE_PREFIX,
    ZONE_CODE_PREFIX,
    SUPPLIER_CODE_PREFIX,
    TRANSACTION_CODE_PREFIX,
    SYNC_CODE_PREFIX,
    PURCHASE_ORDER_PREFIX,
    DOCUMENT_PREFIX,
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


def generate_order_no(prefix: Optional[str] = None) -> str:
    prefix = prefix or PURCHASE_ORDER_PREFIX
    timestamp = datetime.utcnow().strftime("%Y%m%d%H%M%S")
    random_chars = "".join(random.choices(string.ascii_uppercase + string.digits, k=6))
    return f"{prefix}-{timestamp}-{random_chars}"


def generate_batch_no() -> str:
    timestamp = datetime.utcnow().strftime("%Y%m%d")
    random_chars = "".join(random.choices(string.ascii_uppercase + string.digits, k=8))
    return f"BATCH-{timestamp}-{random_chars}"


def generate_document_no(doc_type: str = "INV") -> str:
    timestamp = datetime.utcnow().strftime("%Y%m%d%H%M%S")
    random_chars = "".join(random.choices(string.ascii_uppercase + string.digits, k=6))
    return f"{DOCUMENT_PREFIX}-{doc_type}-{timestamp}-{random_chars}"


def generate_sku_code(product_id: int, attributes: Optional[dict] = None) -> str:
    import hashlib
    attr_str = ""
    if attributes:
        attr_parts = []
        for key in sorted(attributes.keys()):
            attr_parts.append(f"{key}:{attributes[key]}")
        attr_str = "|".join(attr_parts)
    hash_suffix = hashlib.md5(attr_str.encode()).hexdigest()[:8].upper()
    return f"PRD{product_id:06d}-{hash_suffix}"


def calculate_safety_stock(
    avg_daily_demand: float,
    lead_time_days: int,
    service_level: float = 1.65,
    demand_std_dev: float = 0.0,
) -> float:
    demand_variability = demand_std_dev * math.sqrt(lead_time_days)
    safety_stock = service_level * demand_variability
    return max(0.0, round(safety_stock, 2))


def calculate_reorder_point(
    avg_daily_demand: float,
    lead_time_days: int,
    safety_stock: Optional[float] = None,
) -> float:
    if safety_stock is None:
        safety_stock = calculate_safety_stock(avg_daily_demand, lead_time_days)
    return round(avg_daily_demand * lead_time_days + safety_stock, 2)


def calculate_eoq(
    annual_demand: float,
    ordering_cost: float,
    holding_cost_per_unit: float,
) -> float:
    if holding_cost_per_unit <= 0:
        return 0.0
    eoq = math.sqrt((2 * annual_demand * ordering_cost) / holding_cost_per_unit)
    return round(eoq, 2)


def parse_date(date_str: str, fmt: str = "%Y-%m-%d") -> datetime:
    dt = datetime.strptime(date_str, fmt)
    return dt.replace(tzinfo=timezone.utc)


def format_date(dt: datetime, fmt: str = "%Y-%m-%d") -> str:
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.strftime(fmt)


def get_date_range(
    start_date: datetime,
    end_date: datetime,
) -> list[datetime]:
    dates = []
    current = start_date.replace(hour=0, minute=0, second=0, microsecond=0)
    end = end_date.replace(hour=0, minute=0, second=0, microsecond=0)
    while current <= end:
        dates.append(current)
        current += timedelta(days=1)
    return dates


def days_between(start_date: datetime, end_date: datetime) -> int:
    if start_date.tzinfo is None:
        start_date = start_date.replace(tzinfo=timezone.utc)
    if end_date.tzinfo is None:
        end_date = end_date.replace(tzinfo=timezone.utc)
    delta = end_date.date() - start_date.date()
    return abs(delta.days)
