import re
import threading
from collections import defaultdict, deque
from typing import List, Dict, Tuple, Optional, Callable
from datetime import datetime, timedelta

from logtrace.core.config import ConfigManager
from logtrace.core.models import ExceptionRule, LogRecord, ExceptionContext


class LogBuffer:
    def __init__(self, max_age_seconds: int = 30, max_size: int = 10000):
        self.max_age_seconds = max_age_seconds
        self.max_size = max_size
        self._buffers: Dict[str, deque] = defaultdict(deque)
        self._lock = threading.Lock()

    def add_log(self, log: LogRecord):
        with self._lock:
            buffer = self._buffers[log.node_id]
            buffer.append(log)
            if len(buffer) > self.max_size:
                buffer.popleft()
            self._cleanup_old_logs(log.node_id, buffer)

    def _cleanup_old_logs(self, node_id: str, buffer: deque):
        cutoff_time = datetime.utcnow() - timedelta(seconds=self.max_age_seconds)
        while buffer and buffer[0].timestamp < cutoff_time:
            buffer.popleft()

    def get_logs_in_time_range(
        self,
        node_id: str,
        start_time: datetime,
        end_time: datetime
    ) -> List[LogRecord]:
        with self._lock:
            buffer = self._buffers.get(node_id, deque())
            return [
                log for log in buffer
                if start_time <= log.timestamp <= end_time
            ]

    def get_logs_before(
        self,
        node_id: str,
        reference_time: datetime,
        window_seconds: int
    ) -> List[LogRecord]:
        start_time = reference_time - timedelta(seconds=window_seconds)
        return self.get_logs_in_time_range(node_id, start_time, reference_time)

    def get_logs_after(
        self,
        node_id: str,
        reference_time: datetime,
        window_seconds: int
    ) -> List[LogRecord]:
        end_time = reference_time + timedelta(seconds=window_seconds)
        return self.get_logs_in_time_range(node_id, reference_time, end_time)


class AnomalyDetector:
    def __init__(self, config: ConfigManager, enable_context: bool = True):
        self.config = config
        self.rules: List[ExceptionRule] = []
        self._load_rules()
        self.exception_counters: Dict[Tuple[str, str], int] = defaultdict(int)
        self._lock = threading.Lock()
        self.on_alert_triggered: Optional[Callable] = None
        self.on_context_created: Optional[Callable] = None
        self.enable_context = enable_context
        if enable_context:
            self.log_buffer = LogBuffer(max_age_seconds=60, max_size=50000)
        self.exception_contexts: Dict[str, ExceptionContext] = {}

    def _load_rules(self):
        for rule_data in self.config.get_exception_rules():
            self.rules.append(ExceptionRule.from_dict(rule_data))

    def add_rule(self, rule: ExceptionRule):
        self.rules.append(rule)

    def process_logs(self, logs: List[LogRecord]) -> List[LogRecord]:
        results = []
        for log in logs:
            if self.enable_context:
                self.log_buffer.add_log(log)
            marked_log = self._process_single_log(log)
            results.append(marked_log)
        return results

    def _process_single_log(self, log: LogRecord) -> LogRecord:
        for rule in self.rules:
            if self._matches_rule(log, rule):
                log.is_exception = True
                log.exception_type = rule.rule_name
                log.matched_rule_id = rule.rule_id

                if self.enable_context:
                    context = self._build_context(log, rule)
                    if context:
                        log.context_id = context.context_id
                        self.exception_contexts[context.context_id] = context
                        if self.on_context_created:
                            try:
                                self.on_context_created(context)
                            except Exception as e:
                                print(f"Error in context callback: {e}")

                with self._lock:
                    key = (rule.rule_id, log.node_id)
                    self.exception_counters[key] += 1
                    current_count = self.exception_counters[key]

                if rule.alert_enabled and current_count >= rule.alert_threshold:
                    self._trigger_alert(rule, log.node_id, current_count, log.context_id)
                    with self._lock:
                        self.exception_counters[key] = 0
                break
        return log

    def _build_context(self, exception_log: LogRecord, rule: ExceptionRule) -> Optional[ExceptionContext]:
        try:
            context = ExceptionContext.create(
                exception_log_id=exception_log.log_id,
                node_id=exception_log.node_id,
                rule_id=rule.rule_id,
                rule_name=rule.rule_name,
                exception_time=exception_log.timestamp,
                before_window_seconds=rule.context_before_seconds,
                after_window_seconds=rule.context_after_seconds
            )

            logs_before = self.log_buffer.get_logs_before(
                exception_log.node_id,
                exception_log.timestamp,
                rule.context_before_seconds
            )
            for log in logs_before:
                if log.log_id != exception_log.log_id:
                    context.add_context_before(log.to_dict())

            logs_after = self.log_buffer.get_logs_after(
                exception_log.node_id,
                exception_log.timestamp,
                rule.context_after_seconds
            )
            for log in logs_after:
                if log.log_id != exception_log.log_id:
                    context.add_context_after(log.to_dict())

            return context
        except Exception as e:
            print(f"Error building context: {e}")
            return None

    def _matches_rule(self, log: LogRecord, rule: ExceptionRule) -> bool:
        if rule.log_level_filter and log.log_level not in rule.log_level_filter:
            return False
        try:
            pattern = re.compile(rule.pattern, re.IGNORECASE)
            if pattern.search(log.log_content):
                return True
        except re.error as e:
            print(f"Invalid regex pattern for rule {rule.rule_id}: {e}")
        return False

    def _trigger_alert(
        self,
        rule: ExceptionRule,
        node_id: str,
        count: int,
        context_id: Optional[str] = None
    ):
        if self.on_alert_triggered:
            try:
                alert_data = {
                    'rule_id': rule.rule_id,
                    'rule_name': rule.rule_name,
                    'node_id': node_id,
                    'exception_count': count,
                    'severity': rule.severity,
                    'alert_time': datetime.utcnow(),
                    'context_id': context_id
                }
                self.on_alert_triggered(alert_data)
            except Exception as e:
                print(f"Error triggering alert: {e}")

    def set_alert_callback(self, callback: Callable):
        self.on_alert_triggered = callback

    def set_context_callback(self, callback: Callable):
        self.on_context_created = callback

    def get_exception_counters(self) -> Dict[str, Dict[str, int]]:
        with self._lock:
            result: Dict[str, Dict[str, int]] = {}
            for (rule_id, node_id), count in self.exception_counters.items():
                if rule_id not in result:
                    result[rule_id] = {}
                result[rule_id][node_id] = count
            return result

    def get_context(self, context_id: str) -> Optional[ExceptionContext]:
        return self.exception_contexts.get(context_id)

    def get_all_contexts(self) -> List[ExceptionContext]:
        return list(self.exception_contexts.values())

    def get_context_count(self) -> int:
        return len(self.exception_contexts)

    def reset_counters(self):
        with self._lock:
            self.exception_counters.clear()

    def clear_contexts(self):
        self.exception_contexts.clear()

    def get_detector_stats(self) -> Dict[str, int]:
        return {
            'total_rules': len(self.rules),
            'exception_counter_count': len(self.exception_counters),
            'context_count': self.get_context_count(),
            'context_enabled': self.enable_context
        }
