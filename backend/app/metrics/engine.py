from typing import Dict, Any, Optional, List, Callable
from datetime import datetime
import json
import logging

from app.metrics.window import SlidingWindow, WindowBucket
from app.core.models import (
    MetricConfig,
    MetricResult,
    CleanedDataEvent,
    AggregationType
)

logger = logging.getLogger(__name__)


class MetricEngine:
    def __init__(self, config: MetricConfig):
        self.config = config
        self.metric_id = config.metric_id
        self._window = SlidingWindow(config.time_window)
        self._current_bucket: Optional[WindowBucket] = None
        self._previous_bucket_values: Dict[str, float] = {}
        self._on_result_callback: Optional[Callable[[MetricResult], None]] = None
        self._last_finalized_bucket: Optional[WindowBucket] = None

    def set_result_callback(self, callback: Callable[[MetricResult], None]):
        self._on_result_callback = callback

    def _extract_group_key(self, data: Dict[str, Any]) -> Dict[str, Any]:
        if not self.config.group_by:
            return {}

        group_key = {}
        for field in self.config.group_by:
            group_key[field] = data.get(field)

        return group_key

    def _serialize_group_key(self, group_key: Dict[str, Any]) -> str:
        return json.dumps(group_key, sort_keys=True, default=str)

    def _extract_value(self, data: Dict[str, Any]) -> float:
        if self.config.aggregation == AggregationType.COUNT:
            return 1.0

        if not self.config.field:
            logger.error(
                f"Field not specified for {self.config.aggregation} aggregation"
            )
            return 0.0

        value = data.get(self.config.field)

        if value is None:
            logger.warning(
                f"Field '{self.config.field}' not found in data, using 0.0"
            )
            return 0.0

        try:
            return float(value)
        except (TypeError, ValueError):
            logger.warning(
                f"Could not convert '{value}' to float, using 0.0"
            )
            return 0.0

    def _ensure_bucket(self, timestamp: datetime) -> WindowBucket:
        window_start = self._window.get_window_start(timestamp)

        if self._current_bucket is None:
            self._current_bucket = WindowBucket(
                window_start,
                self._window.window_seconds
            )
            if self._previous_bucket_values:
                self._current_bucket.set_previous_values(self._previous_bucket_values)
            return self._current_bucket

        if self._window.is_new_window(timestamp, self._current_bucket.window_start):
            self._finalize_current_bucket()
            self._current_bucket = WindowBucket(
                window_start,
                self._window.window_seconds
            )
            if self._previous_bucket_values:
                self._current_bucket.set_previous_values(self._previous_bucket_values)

        return self._current_bucket

    def _finalize_current_bucket(self):
        if not self._current_bucket or self._current_bucket.is_empty:
            return

        bucket = self._current_bucket
        bucket.finalize()

        self._last_finalized_bucket = bucket

        for group_key_str in bucket.get_group_keys():
            group_key = json.loads(group_key_str)
            value = bucket.get_aggregation(
                group_key_str,
                self.config.aggregation.value
            )

            if value is not None:
                self._previous_bucket_values[group_key_str] = value

                result = MetricResult(
                    metric_id=self.metric_id,
                    value=value,
                    timestamp=bucket.window_end,
                    group_key=group_key,
                    window_end=True,
                    window_start=bucket.window_start
                )

                if self._on_result_callback:
                    try:
                        self._on_result_callback(result)
                    except Exception as e:
                        logger.error(f"Error in metric result callback: {e}")

    def process_event(self, event: CleanedDataEvent) -> Optional[MetricResult]:
        if event.source != self.config.source:
            return None

        if not self.config.is_active:
            return None

        try:
            group_key = self._extract_group_key(event.data)
            group_key_str = self._serialize_group_key(group_key)
            value = self._extract_value(event.data)

            bucket = self._ensure_bucket(event.timestamp)
            bucket.add_event(group_key_str, value)

            current_value = bucket.get_aggregation(
                group_key_str,
                self.config.aggregation.value
            )

            if current_value is not None:
                return MetricResult(
                    metric_id=self.metric_id,
                    value=current_value,
                    timestamp=event.timestamp,
                    group_key=group_key,
                    window_end=False,
                    window_start=bucket.window_start
                )

            return None

        except Exception as e:
            logger.error(f"Error processing event in metric engine: {e}")
            return None

    def flush_window(self, current_time: datetime = None):
        if current_time is None:
            current_time = datetime.utcnow()

        if self._current_bucket:
            self._finalize_current_bucket()
            self._current_bucket = WindowBucket(
                self._window.get_window_start(current_time),
                self._window.window_seconds
            )

    def get_current_value(self, group_key: Dict[str, Any] = None) -> Optional[float]:
        if not self._current_bucket:
            return None

        if group_key is None:
            group_key = {}

        group_key_str = self._serialize_group_key(group_key)
        return self._current_bucket.get_aggregation(
            group_key_str,
            self.config.aggregation.value
        )

    def get_window_progress(self, timestamp: datetime = None) -> float:
        if timestamp is None:
            timestamp = datetime.utcnow()

        if self._current_bucket:
            return self._window.get_window_progress(
                timestamp,
                self._current_bucket.window_start
            )
        return 0.0

    def update_config(self, config: MetricConfig):
        if config.metric_id != self.metric_id:
            logger.error("Cannot update config with different metric_id")
            return

        self.config = config
        self._window = SlidingWindow(config.time_window)

        if self._current_bucket:
            current_time = datetime.utcnow()
            self._current_bucket = WindowBucket(
                self._window.get_window_start(current_time),
                self._window.window_seconds
            )

        logger.info(f"Updated config for metric: {self.metric_id}")
