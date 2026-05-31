"""
Utility functions for the platform.
"""

import asyncio
import functools
import hashlib
import json
import random
import string
import time
import uuid
from contextlib import contextmanager
from datetime import datetime, timedelta
from typing import Any, Callable, Dict, Generator, List, Optional, TypeVar

T = TypeVar('T')


def generate_id(prefix: str = "", length: int = 8) -> str:
    random_part = ''.join(
        random.choices(string.ascii_lowercase + string.digits, k=length)
    )
    if prefix:
        return f"{prefix}_{random_part}"
    return random_part


def generate_uuid() -> str:
    return str(uuid.uuid4())


def hash_string(value: str, algorithm: str = "sha256") -> str:
    h = hashlib.new(algorithm)
    h.update(value.encode("utf-8"))
    return h.hexdigest()


def now_iso() -> str:
    return datetime.utcnow().isoformat() + "Z"


def now_epoch() -> float:
    return time.time()


def parse_iso_datetime(s: str) -> datetime:
    if s.endswith("Z"):
        s = s[:-1] + "+00:00"
    return datetime.fromisoformat(s)


def format_datetime(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%d %H:%M:%S")


def timedelta_to_seconds(td: timedelta) -> float:
    return td.total_seconds()


def json_dumps(obj: Any) -> str:
    def default(o):
        if isinstance(o, datetime):
            return o.isoformat()
        if isinstance(o, timedelta):
            return o.total_seconds()
        if hasattr(o, "to_dict"):
            return o.to_dict()
        if hasattr(o, "__dict__"):
            return o.__dict__
        raise TypeError(f"Object of type {type(o)} is not JSON serializable")
    return json.dumps(obj, default=default, ensure_ascii=False)


def json_loads(s: str) -> Any:
    return json.loads(s)


def chunk_list(lst: List[T], chunk_size: int) -> Generator[List[T], None, None]:
    for i in range(0, len(lst), chunk_size):
        yield lst[i:i + chunk_size]


def deep_merge(base: Dict[str, Any], override: Dict[str, Any]) -> Dict[str, Any]:
    result = {**base}
    for key, value in override.items():
        if key in result and isinstance(result[key], dict) and isinstance(value, dict):
            result[key] = deep_merge(result[key], value)
        else:
            result[key] = value
    return result


def retry(
    max_attempts: int = 3,
    delay_seconds: float = 1.0,
    backoff_factor: float = 2.0,
    exceptions: tuple = (Exception,)
):
    def decorator(func: Callable[..., T]) -> Callable[..., T]:
        @functools.wraps(func)
        def wrapper(*args, **kwargs) -> T:
            last_exception = None
            for attempt in range(max_attempts):
                try:
                    return func(*args, **kwargs)
                except exceptions as e:
                    last_exception = e
                    if attempt < max_attempts - 1:
                        wait_time = delay_seconds * (backoff_factor ** attempt)
                        time.sleep(wait_time)
            raise last_exception or Exception("Retry failed")
        return wrapper
    return decorator


def async_retry(
    max_attempts: int = 3,
    delay_seconds: float = 1.0,
    backoff_factor: float = 2.0,
    exceptions: tuple = (Exception,)
):
    def decorator(func: Callable[..., T]) -> Callable[..., T]:
        @functools.wraps(func)
        async def wrapper(*args, **kwargs) -> T:
            last_exception = None
            for attempt in range(max_attempts):
                try:
                    return await func(*args, **kwargs)
                except exceptions as e:
                    last_exception = e
                    if attempt < max_attempts - 1:
                        wait_time = delay_seconds * (backoff_factor ** attempt)
                        await asyncio.sleep(wait_time)
            raise last_exception or Exception("Retry failed")
        return wrapper
    return decorator


class Timer:
    def __init__(self, name: str = "", autostart: bool = True):
        self.name = name
        self._start_time: Optional[float] = None
        self._end_time: Optional[float] = None
        if autostart:
            self.start()
    
    def start(self):
        self._start_time = time.perf_counter()
        self._end_time = None
    
    def stop(self) -> float:
        self._end_time = time.perf_counter()
        return self.elapsed()
    
    def elapsed(self) -> float:
        if self._start_time is None:
            return 0.0
        end = self._end_time or time.perf_counter()
        return end - self._start_time
    
    def __enter__(self):
        self.start()
        return self
    
    def __exit__(self, exc_type, exc_val, exc_tb):
        self.stop()
        return False


class SingletonMeta(type):
    _instances: Dict[type, Any] = {}
    
    def __call__(cls, *args, **kwargs):
        if cls not in cls._instances:
            cls._instances[cls] = super().__call__(*args, **kwargs)
        return cls._instances[cls]


class MetricsCollector:
    def __init__(self):
        self._counters: Dict[str, int] = {}
        self._gauges: Dict[str, float] = {}
        self._histograms: Dict[str, List[float]] = {}
    
    def increment(self, name: str, value: int = 1):
        self._counters[name] = self._counters.get(name, 0) + value
    
    def gauge(self, name: str, value: float):
        self._gauges[name] = value
    
    def histogram(self, name: str, value: float):
        if name not in self._histograms:
            self._histograms[name] = []
        self._histograms[name].append(value)
    
    def get_counter(self, name: str) -> int:
        return self._counters.get(name, 0)
    
    def get_gauge(self, name: str) -> float:
        return self._gauges.get(name, 0.0)
    
    def get_histogram_stats(self, name: str) -> Dict[str, float]:
        values = self._histograms.get(name, [])
        if not values:
            return {"count": 0.0, "avg": 0.0, "min": 0.0, "max": 0.0}
        return {
            "count": len(values),
            "avg": sum(values) / len(values),
            "min": min(values),
            "max": max(values)
        }
    
    def reset(self):
        self._counters.clear()
        self._gauges.clear()
        self._histograms.clear()
