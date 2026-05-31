import hashlib
import json
import secrets
import string
from datetime import datetime
from typing import Any, Dict
from uuid import uuid4


def generate_id(prefix: str = "") -> str:
    return f"{prefix}{uuid4().hex[:12]}"


def hash_data(data: Any, algorithm: str = "sha256") -> str:
    if isinstance(data, (dict, list)):
        data_str = json.dumps(data, sort_keys=True, default=str)
    else:
        data_str = str(data)
    h = hashlib.new(algorithm)
    h.update(data_str.encode("utf-8"))
    return h.hexdigest()


def generate_random_string(length: int = 32) -> str:
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(length))


def generate_random_bytes(length: int = 32) -> bytes:
    return secrets.token_bytes(length)


def datetime_to_str(dt: datetime) -> str:
    return dt.isoformat()


def str_to_datetime(s: str) -> datetime:
    return datetime.fromisoformat(s)


def validate_required_fields(data: Dict[str, Any], required: list) -> list:
    missing = []
    for field in required:
        if field not in data or data[field] is None:
            missing.append(field)
    return missing
