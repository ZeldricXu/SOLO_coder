from typing import Dict, Any, Optional
from datetime import datetime, timedelta
import re
import logging

logger = logging.getLogger(__name__)


class SlidingWindow:
    def __init__(self, window_str: str):
        self._window_str = window_str
        self._window_seconds = self._parse_window(window_str)
        self._current_window_start: Optional[datetime] = None
        self._window_end_triggered = False

    def _parse_window(self, window_str: str) -> int:
        pattern = r'^(\d+)(s|m|h|d)$'
        match = re.match(pattern, window_str.lower().strip())
        if not match:
            logger.warning(f"Invalid window format: {window_str}, using default 60s")
            return 60

        value = int(match.group(1))
        unit = match.group(2)

        multipliers = {
            's': 1,
            'm': 60,
            'h': 3600,
            'd': 86400
        }

        return value * multipliers.get(unit, 1)

    @property
    def window_seconds(self) -> int:
        return self._window_seconds

    @property
    def window_str(self) -> str:
        return self._window_str

    def get_window_start(self, timestamp: datetime) -> datetime:
        if self._window_seconds <= 0:
            return timestamp

        total_seconds = int(timestamp.timestamp())
        window_start_seconds = (total_seconds // self._window_seconds) * self._window_seconds
        return datetime.fromtimestamp(window_start_seconds)

    def get_window_end(self, window_start: datetime) -> datetime:
        return window_start + timedelta(seconds=self._window_seconds)

    def is_new_window(self, timestamp: datetime, current_window_start: datetime) -> bool:
        new_window_start = self.get_window_start(timestamp)
        return new_window_start > current_window_start

    def get_window_progress(self, timestamp: datetime, window_start: datetime) -> float:
        if self._window_seconds <= 0:
            return 1.0
        elapsed = (timestamp - window_start).total_seconds()
        return min(1.0, max(0.0, elapsed / self._window_seconds))


class WindowBucket:
    def __init__(self, window_start: datetime, window_seconds: int):
        self.window_start = window_start
        self.window_seconds = window_seconds
        self.window_end = window_start + timedelta(seconds=window_seconds)
        self._data: Dict[str, Any] = {}
        self._event_count = 0
        self._finalized = False
        self._previous_values: Dict[str, float] = {}

    def set_previous_values(self, previous_values: Dict[str, float]):
        self._previous_values = previous_values.copy()

    def add_event(self, group_key: str, value: float = 1.0):
        if self._finalized:
            logger.warning("Cannot add event to finalized bucket")
            return

        if group_key not in self._data:
            self._data[group_key] = {
                'sum': 0.0,
                'count': 0,
                'values': []
            }

        self._data[group_key]['sum'] += value
        self._data[group_key]['count'] += 1
        self._data[group_key]['values'].append(value)
        self._event_count += 1

    def get_group_keys(self) -> list:
        return list(self._data.keys())

    def get_aggregation(self, group_key: str, aggregation_type: str) -> Optional[float]:
        if group_key not in self._data:
            return self._previous_values.get(group_key)

        data = self._data[group_key]

        if aggregation_type == 'count':
            return float(data['count'])
        elif aggregation_type == 'sum':
            return data['sum']
        elif aggregation_type == 'avg':
            if data['count'] == 0:
                logger.warning(
                    f"Avg aggregation with count=0 for group_key: {group_key}"
                )
                return self._previous_values.get(group_key)
            try:
                return data['sum'] / data['count']
            except ZeroDivisionError:
                logger.error(
                    f"Unexpected ZeroDivisionError in avg aggregation, "
                    f"sum={data['sum']}, count={data['count']}"
                )
                return self._previous_values.get(group_key)
        else:
            logger.warning(f"Unknown aggregation type: {aggregation_type}")
            return None

    def get_final_aggregations(self) -> Dict[str, float]:
        result = {}
        for group_key in self._data.keys():
            count_val = self.get_aggregation(group_key, 'count')
            if count_val is not None:
                result[group_key + '_count'] = count_val
        return result

    def finalize(self):
        self._finalized = True

    @property
    def event_count(self) -> int:
        return self._event_count

    @property
    def is_empty(self) -> bool:
        return self._event_count == 0

    def reset(self):
        self._data.clear()
        self._event_count = 0
        self._finalized = False
        self._previous_values.clear()
