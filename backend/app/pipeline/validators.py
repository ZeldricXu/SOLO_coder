from typing import Any, Callable, Dict, List
import re
import logging

logger = logging.getLogger(__name__)

ValidatorFunc = Callable[[Any], tuple[bool, str]]


class DataValidators:
    _validators: Dict[str, ValidatorFunc] = {}

    @classmethod
    def register(cls, name: str, validator: ValidatorFunc):
        cls._validators[name] = validator

    @classmethod
    def validate(cls, value: Any, validator_name: str) -> tuple[bool, str]:
        validator = cls._validators.get(validator_name)
        if not validator:
            return True, f"Validator '{validator_name}' not found, skipping"
        return validator(value)

    @classmethod
    def validate_all(cls, value: Any, validator_names: List[str]) -> tuple[bool, List[str]]:
        errors = []
        for name in validator_names:
            valid, msg = cls.validate(value, name)
            if not valid:
                errors.append(msg)
        return len(errors) == 0, errors


def not_null(value: Any) -> tuple[bool, str]:
    if value is None:
        return False, "Value cannot be null"
    return True, ""


def not_empty(value: Any) -> tuple[bool, str]:
    if value is None:
        return False, "Value cannot be null"
    if isinstance(value, str) and value.strip() == "":
        return False, "String cannot be empty"
    if isinstance(value, (list, dict)) and len(value) == 0:
        return False, "Collection cannot be empty"
    return True, ""


def is_positive(value: Any) -> tuple[bool, str]:
    try:
        num = float(value)
        if num <= 0:
            return False, f"Value {value} must be positive"
        return True, ""
    except (TypeError, ValueError):
        return False, f"Value {value} is not a valid number"


def is_non_negative(value: Any) -> tuple[bool, str]:
    try:
        num = float(value)
        if num < 0:
            return False, f"Value {value} must be non-negative"
        return True, ""
    except (TypeError, ValueError):
        return False, f"Value {value} is not a valid number"


def is_email(value: Any) -> tuple[bool, str]:
    if not isinstance(value, str):
        return False, "Email must be a string"
    pattern = r'^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$'
    if not re.match(pattern, value):
        return False, f"'{value}' is not a valid email format"
    return True, ""


def min_length(value: Any, min_len: int) -> tuple[bool, str]:
    if not hasattr(value, '__len__'):
        return False, f"Value does not support length check"
    if len(value) < min_len:
        return False, f"Length {len(value)} is less than minimum {min_len}"
    return True, ""


def max_length(value: Any, max_len: int) -> tuple[bool, str]:
    if not hasattr(value, '__len__'):
        return False, f"Value does not support length check"
    if len(value) > max_len:
        return False, f"Length {len(value)} exceeds maximum {max_len}"
    return True, ""


def in_range(value: Any, min_val: float, max_val: float) -> tuple[bool, str]:
    try:
        num = float(value)
        if num < min_val or num > max_val:
            return False, f"Value {value} is out of range [{min_val}, {max_val}]"
        return True, ""
    except (TypeError, ValueError):
        return False, f"Value {value} is not a valid number"


def in_list(value: Any, allowed: List[Any]) -> tuple[bool, str]:
    if value not in allowed:
        return False, f"Value {value} is not in allowed list: {allowed}"
    return True, ""


def is_date(value: Any) -> tuple[bool, str]:
    from datetime import datetime
    if isinstance(value, datetime):
        return True, ""
    if not isinstance(value, str):
        return False, "Date must be a string or datetime object"

    formats = [
        "%Y-%m-%d",
        "%Y/%m/%d",
        "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%dT%H:%M:%SZ",
    ]

    for fmt in formats:
        try:
            datetime.strptime(value, fmt)
            return True, ""
        except ValueError:
            continue

    return False, f"'{value}' is not a valid date format"


DataValidators.register("not_null", not_null)
DataValidators.register("not_empty", not_empty)
DataValidators.register("is_positive", is_positive)
DataValidators.register("is_non_negative", is_non_negative)
DataValidators.register("is_email", is_email)
DataValidators.register("is_date", is_date)
