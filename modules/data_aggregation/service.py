from typing import Optional, Dict, Any, List, Tuple
from datetime import datetime, timedelta
from collections import defaultdict, deque
from enum import Enum
import threading
import time
import uuid
import math

from domain.models.telemetry import TelemetryData, AggregatedData
from domain.models.event import EventType

from infrastructure.persistence.repositories.telemetry_repository import TelemetryRepository
from infrastructure.messaging.event_bus import EventBus, get_event_bus
from infrastructure.logging.logger import get_logger

logger = get_logger(__name__)


class AggregationErrorCode(str, Enum):
    RULE_NOT_FOUND = "RULE_NOT_FOUND"
    INVALID_RULE = "INVALID_RULE"
    INVALID_METRIC = "INVALID_METRIC"
    BUFFER_OVERFLOW = "BUFFER_OVERFLOW"
    AGGREGATION_FAILED = "AGGREGATION_FAILED"


class AggregationError(Exception):
    def __init__(self, code: AggregationErrorCode, message: str):
        self.code = code
        self.message = message
        super().__init__(message)


_VALID_AGG_TYPES = frozenset({"avg", "sum", "min", "max", "count", "std_dev"})
_DEFAULT_PERIOD_SECONDS = 60
_DEFAULT_MAX_BUFFER_SIZE = 10000
_CLEANUP_RETENTION_HOURS = 24
_AGGREGATION_LOOP_INTERVAL = 60


class _DataBuffer:
    def __init__(self, max_size: int = _DEFAULT_MAX_BUFFER_SIZE):
        self._buffers: Dict[str, List[TelemetryData]] = defaultdict(list)
        self._lock = threading.Lock()
        self._max_size = max_size

    def append(self, device_id: str, telemetry: TelemetryData) -> None:
        with self._lock:
            buf = self._buffers[device_id]
            buf.append(telemetry)
            if len(buf) > self._max_size:
                half = self._max_size // 2
                del buf[:len(buf) - half]

    def get_in_range(
        self,
        device_id: str,
        metric: str,
        period_start: datetime,
        period_end: datetime,
    ) -> List:
        with self._lock:
            buf = self._buffers.get(device_id)
            if buf is None:
                return []

            points = []
            for telemetry in buf:
                if period_start <= telemetry.timestamp <= period_end:
                    if metric in telemetry.data:
                        points.append(telemetry.data[metric])
            return points

    def clear(self, device_id: Optional[str] = None) -> None:
        with self._lock:
            if device_id:
                self._buffers[device_id] = []
            else:
                self._buffers.clear()

    def cleanup_before(self, cutoff: datetime) -> None:
        with self._lock:
            for device_id in self._buffers:
                buf = self._buffers[device_id]
                original_len = len(buf)
                write_idx = 0
                for i in range(original_len):
                    if buf[i].timestamp > cutoff:
                        buf[write_idx] = buf[i]
                        write_idx += 1
                del buf[write_idx:]

    def device_ids(self) -> List[str]:
        with self._lock:
            return list(self._buffers.keys())

    def total_points(self) -> int:
        with self._lock:
            return sum(len(buf) for buf in self._buffers.values())


class _AggregationRuleStore:
    def __init__(self):
        self._rules: Dict[str, Dict[str, Any]] = {}
        self._lock = threading.RLock()

    def put(self, rule_id: str, rule: Dict[str, Any]) -> None:
        with self._lock:
            self._rules[rule_id] = rule

    def remove(self, rule_id: str) -> bool:
        with self._lock:
            if rule_id in self._rules:
                del self._rules[rule_id]
                return True
            return False

    def get_all(self) -> Dict[str, Dict[str, Any]]:
        with self._lock:
            return dict(self._rules)

    def count(self) -> int:
        with self._lock:
            return len(self._rules)


class _Statistics:
    __slots__ = ("count", "sum_value", "min_value", "max_value", "avg_value", "std_dev")

    def __init__(self, values: List[float]):
        self.count = len(values)
        if self.count == 0:
            self.sum_value = 0.0
            self.min_value = None
            self.max_value = None
            self.avg_value = None
            self.std_dev = None
            return

        self.sum_value = math.fsum(values)
        self.min_value = min(values)
        self.max_value = max(values)
        self.avg_value = self.sum_value / self.count

        if self.count >= 2:
            variance = math.fsum((x - self.avg_value) ** 2 for x in values) / self.count
            self.std_dev = variance ** 0.5
        else:
            self.std_dev = None

    def get_value(self, agg_type: str) -> Optional[float]:
        mapping = {
            "avg": self.avg_value,
            "sum": self.sum_value,
            "min": self.min_value,
            "max": self.max_value,
            "count": float(self.count),
            "std_dev": self.std_dev,
        }
        return mapping.get(agg_type)


class DataAggregationService:
    def __init__(
        self,
        telemetry_repo: TelemetryRepository,
        event_bus: Optional[EventBus] = None,
        max_buffer_size: int = _DEFAULT_MAX_BUFFER_SIZE,
        default_period_seconds: int = _DEFAULT_PERIOD_SECONDS,
    ):
        self._repo = telemetry_repo
        self._event_bus = event_bus or get_event_bus()

        self._buffer = _DataBuffer(max_buffer_size)
        self._rules = _AggregationRuleStore()
        self._last_aggregation_time: Dict[str, datetime] = {}
        self._agg_time_lock = threading.Lock()

        self._default_period_seconds = default_period_seconds

        self._aggregation_thread: Optional[threading.Thread] = None
        self._stop_event = threading.Event()
        self._is_running = False

    def add_aggregation_rule(
        self,
        device_id: str,
        metric: str,
        aggregation_type: str,
        interval_seconds: int,
    ) -> str:
        self._validate_rule_params(device_id, metric, aggregation_type, interval_seconds)

        rule_id = str(uuid.uuid4())
        self._rules.put(rule_id, {
            "rule_id": rule_id,
            "device_id": device_id,
            "metric": metric,
            "aggregation_types": [aggregation_type],
            "period_seconds": interval_seconds,
        })

        logger.info(
            "Aggregation rule added",
            extra={"rule_id": rule_id, "device_id": device_id, "metric": metric},
        )
        return rule_id

    def add_aggregation_rule_direct(
        self,
        rule_id: str,
        device_id: str,
        metric: str,
        aggregation_types: List[str],
        period_seconds: int = 60,
    ) -> None:
        self._rules.put(rule_id, {
            "device_id": device_id,
            "metric": metric,
            "aggregation_types": aggregation_types,
            "period_seconds": period_seconds,
        })
        logger.info(
            "Aggregation rule added",
            extra={"rule_id": rule_id, "device_id": device_id, "metric": metric},
        )

    def remove_aggregation_rule(self, rule_id: str) -> bool:
        removed = self._rules.remove(rule_id)
        if removed:
            logger.info("Aggregation rule removed", extra={"rule_id": rule_id})
        return removed

    def list_rules(self, device_id: Optional[str] = None) -> List[Dict[str, Any]]:
        all_rules = self._rules.get_all()
        rules = list(all_rules.values())
        if device_id is not None:
            rules = [r for r in rules if r.get("device_id") == device_id]
        return rules

    def process_telemetry_data(self, telemetry: TelemetryData) -> None:
        self._buffer.append(telemetry.device_id, telemetry)

    def aggregate(
        self,
        device_id: str,
        metric: str,
        period_start: datetime,
        period_end: datetime,
    ) -> List[AggregatedData]:
        data_points = self._buffer.get_in_range(device_id, metric, period_start, period_end)

        values = [dp.value for dp in data_points if isinstance(dp.value, (int, float))]
        if not values:
            return []

        stats = _Statistics(values)

        results = []
        for agg_type in ["avg", "sum", "min", "max", "count"]:
            value = stats.get_value(agg_type)
            if value is not None:
                aggregated = self._build_aggregated_data(
                    device_id=device_id,
                    metric=metric,
                    agg_type=agg_type,
                    period_start=period_start,
                    period_end=period_end,
                    value=value,
                    stats=stats,
                )
                results.append(aggregated)
                self._persist_and_publish(aggregated)

        return results

    def run_aggregation_cycle(self) -> Dict[str, Any]:
        now = datetime.utcnow()
        aggregated_count = 0
        error_count = 0

        for rule_id, rule in self._rules.get_all().items():
            try:
                result = self._process_rule(rule_id, rule, now)
                if result:
                    aggregated_count += 1
            except Exception as exc:
                error_count += 1
                logger.error(
                    "Rule aggregation failed",
                    extra={"rule_id": rule_id, "error": str(exc)},
                )

        self._cleanup_old_data()

        return {
            "rules_processed": self._rules.count(),
            "aggregated_count": aggregated_count,
            "error_count": error_count,
        }

    def get_aggregated_data(
        self,
        device_id: str,
        metric: str,
        aggregation_type: str,
        start_time: datetime,
        end_time: Optional[datetime] = None,
    ) -> Optional[AggregatedData]:
        end_time = end_time or datetime.utcnow()

        db_results = self._repo.get_telemetry_by_device(
            device_id=device_id,
            start_time=start_time,
            end_time=end_time,
            limit=1,
        )

        if db_results:
            data_points = self._buffer.get_in_range(device_id, metric, start_time, end_time)
            values = [dp.value for dp in data_points if isinstance(dp.value, (int, float))]
            if values:
                stats = _Statistics(values)
                value = stats.get_value(aggregation_type)
                if value is not None:
                    return self._build_aggregated_data(
                        device_id=device_id,
                        metric=metric,
                        agg_type=aggregation_type,
                        period_start=start_time,
                        period_end=end_time,
                        value=value,
                        stats=stats,
                    )
        return None

    def get_buffer_status(self) -> Dict[str, Any]:
        return {
            "total_buffered_points": self._buffer.total_points(),
            "active_rules": self._rules.count(),
            "devices": self._buffer.device_ids(),
        }

    def clear_buffer(self, device_id: Optional[str] = None) -> None:
        self._buffer.clear(device_id)
        logger.info("Buffer cleared")

    def start(self) -> None:
        if self._is_running:
            return

        self._is_running = True
        self._stop_event.clear()
        self._aggregation_thread = threading.Thread(target=self._aggregation_loop, daemon=True)
        self._aggregation_thread.start()
        logger.info("Data aggregation service started")

    def stop(self) -> None:
        self._is_running = False
        self._stop_event.set()
        if self._aggregation_thread is not None:
            self._aggregation_thread.join(timeout=5)
        logger.info("Data aggregation service stopped")

    def _aggregation_loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                self.run_aggregation_cycle()
            except Exception as exc:
                logger.error(f"Aggregation loop error: {exc}")

            self._stop_event.wait(_AGGREGATION_LOOP_INTERVAL)

    def _process_rule(
        self,
        rule_id: str,
        rule: Dict[str, Any],
        now: datetime,
    ) -> bool:
        device_id = rule["device_id"]
        metric = rule["metric"]
        period = rule["period_seconds"]

        with self._agg_time_lock:
            last_agg = self._last_aggregation_time.get(rule_id)
            period_start = last_agg or (now - timedelta(seconds=period))
            period_end = now

            if (now - period_start).total_seconds() < period:
                return False

            self.aggregate(device_id, metric, period_start, period_end)
            self._last_aggregation_time[rule_id] = now

        return True

    def _build_aggregated_data(
        self,
        device_id: str,
        metric: str,
        agg_type: str,
        period_start: datetime,
        period_end: datetime,
        value: float,
        stats: _Statistics,
    ) -> AggregatedData:
        return AggregatedData(
            device_id=device_id,
            metric=metric,
            aggregation_type=agg_type,
            period_start=period_start,
            period_end=period_end,
            value=value,
            count=stats.count,
            min_value=stats.min_value,
            max_value=stats.max_value,
            sum_value=stats.sum_value,
            avg_value=stats.avg_value,
            std_dev=stats.std_dev,
        )

    def _persist_and_publish(self, aggregated: AggregatedData) -> None:
        self._repo.save_aggregated_data(aggregated)

        self._publish_event(
            EventType.TELEMETRY_AGGREGATED,
            device_id=aggregated.device_id,
            data=aggregated.model_dump(),
        )

    def _cleanup_old_data(self) -> None:
        cutoff = datetime.utcnow() - timedelta(hours=_CLEANUP_RETENTION_HOURS)
        self._buffer.cleanup_before(cutoff)

    def _validate_rule_params(
        self,
        device_id: str,
        metric: str,
        aggregation_type: str,
        interval_seconds: int,
    ) -> None:
        if not device_id:
            raise AggregationError(AggregationErrorCode.INVALID_RULE, "device_id is required")
        if not metric:
            raise AggregationError(AggregationErrorCode.INVALID_METRIC, "metric is required")
        if aggregation_type not in _VALID_AGG_TYPES:
            raise AggregationError(
                AggregationErrorCode.INVALID_RULE,
                f"Invalid aggregation_type: {aggregation_type}",
            )
        if interval_seconds < 1:
            raise AggregationError(
                AggregationErrorCode.INVALID_RULE,
                "interval_seconds must be >= 1",
            )

    def _publish_event(
        self,
        event_type: EventType,
        device_id: Optional[str] = None,
        data: Optional[Dict[str, Any]] = None,
    ) -> None:
        event = self._event_bus.create_event(
            event_type=event_type,
            device_id=device_id,
            data=data or {},
        )
        self._event_bus.publish(event)
