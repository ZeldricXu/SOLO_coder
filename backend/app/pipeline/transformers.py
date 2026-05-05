from typing import Any, Callable, Dict, Optional
from datetime import datetime, date
import logging

logger = logging.getLogger(__name__)

TransformerFunc = Callable[[Any, Optional[Any]], Any]


class TypeTransformers:
    _transformers: Dict[str, TransformerFunc] = {}

    @classmethod
    def register(cls, type_name: str, transformer: TransformerFunc):
        cls._transformers[type_name] = transformer

    @classmethod
    def transform(cls, value: Any, target_type: str, default: Any = None) -> Any:
        transformer = cls._transformers.get(target_type)
        if not transformer:
            logger.warning(f"Unknown type '{target_type}', returning original value")
            return value
        return transformer(value, default)


def to_string(value: Any, default: Optional[str] = None) -> Optional[str]:
    if value is None:
        return default
    if isinstance(value, str):
        return value.strip()
    return str(value)


def to_integer(value: Any, default: Optional[int] = None) -> Optional[int]:
    if value is None:
        return default
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        return int(value) if value.is_integer() else int(value)
    try:
        s = str(value).strip()
        if '.' in s:
            s = s.split('.')[0]
        return int(s)
    except (ValueError, TypeError):
        logger.warning(f"Failed to convert '{value}' to integer, using default: {default}")
        return default


def to_float(value: Any, default: Optional[float] = None) -> Optional[float]:
    if value is None:
        return default
    if isinstance(value, float):
        return value
    if isinstance(value, int):
        return float(value)
    try:
        return float(str(value).strip())
    except (ValueError, TypeError):
        logger.warning(f"Failed to convert '{value}' to float, using default: {default}")
        return default


def to_boolean(value: Any, default: Optional[bool] = None) -> Optional[bool]:
    if value is None:
        return default
    if isinstance(value, bool):
        return value

    s = str(value).lower().strip()

    truthy_values = {'true', '1', 'yes', 'on', 't', 'y'}
    falsy_values = {'false', '0', 'no', 'off', 'f', 'n'}

    if s in truthy_values:
        return True
    if s in falsy_values:
        return False

    logger.warning(f"Failed to convert '{value}' to boolean, using default: {default}")
    return default


def to_datetime(value: Any, default: Optional[datetime] = None) -> Optional[datetime]:
    if value is None:
        return default
    if isinstance(value, datetime):
        return value
    if isinstance(value, date):
        return datetime(value.year, value.month, value.day)

    s = str(value).strip()

    formats = [
        "%Y-%m-%d %H:%M:%S",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%dT%H:%M:%SZ",
        "%Y-%m-%d",
        "%Y/%m/%d",
        "%d-%m-%Y %H:%M:%S",
        "%m/%d/%Y %H:%M:%S",
    ]

    for fmt in formats:
        try:
            return datetime.strptime(s, fmt)
        except ValueError:
            continue

    try:
        import dateutil.parser
        return dateutil.parser.parse(s)
    except (ImportError, ValueError):
        pass

    logger.warning(f"Failed to convert '{value}' to datetime, using default: {default}")
    return default


def to_list(value: Any, default: Optional[list] = None) -> Optional[list]:
    if value is None:
        return default if default is not None else []
    if isinstance(value, list):
        return value
    if isinstance(value, (str, bytes)):
        s = str(value).strip()
        if s.startswith('[') and s.endswith(']'):
            try:
                import json
                return json.loads(s)
            except json.JSONDecodeError:
                pass
        return [item.strip() for item in s.split(',')]

    try:
        return list(value)
    except (TypeError, ValueError):
        logger.warning(f"Failed to convert '{value}' to list, using default: {default}")
        return default if default is not None else []


def to_dict(value: Any, default: Optional[dict] = None) -> Optional[dict]:
    if value is None:
        return default if default is not None else {}
    if isinstance(value, dict):
        return value

    s = str(value).strip()
    if s.startswith('{') and s.endswith('}'):
        try:
            import json
            return json.loads(s)
        except json.JSONDecodeError:
            pass

    logger.warning(f"Failed to convert '{value}' to dict, using default: {default}")
    return default if default is not None else {}


def trim_string(value: Any, default: Optional[str] = None) -> Optional[str]:
    if value is None:
        return default
    if isinstance(value, str):
        return value.strip()
    return str(value).strip()


def upper_string(value: Any, default: Optional[str] = None) -> Optional[str]:
    if value is None:
        return default
    s = str(value)
    return s.upper()


def lower_string(value: Any, default: Optional[str] = None) -> Optional[str]:
    if value is None:
        return default
    s = str(value)
    return s.lower()


TypeTransformers.register("string", to_string)
TypeTransformers.register("str", to_string)
TypeTransformers.register("integer", to_integer)
TypeTransformers.register("int", to_integer)
TypeTransformers.register("float", to_float)
TypeTransformers.register("double", to_float)
TypeTransformers.register("boolean", to_boolean)
TypeTransformers.register("bool", to_boolean)
TypeTransformers.register("datetime", to_datetime)
TypeTransformers.register("date", to_datetime)
TypeTransformers.register("list", to_list)
TypeTransformers.register("dict", to_dict)
TypeTransformers.register("trim", trim_string)
TypeTransformers.register("upper", upper_string)
TypeTransformers.register("lower", lower_string)
