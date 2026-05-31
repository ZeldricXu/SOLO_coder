"""Logging module for dynamic log level adjustment."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...domain.models.common import EventMessage, ProcessingResult, ProcessingStatus
from ...infrastructure.logging.structured_logger import LogManager
from ...infrastructure.config.settings import Settings
from ...infrastructure.config.default_config import get_default_settings
from .log_level_manager import LogLevelManager, LogLevelRule
from .log_aggregator import LogAggregator, LogEntry


class LoggingModule:
    def __init__(self, settings: Optional[Settings] = None) -> None:
        self._settings = settings or get_default_settings()
        self._level_manager = LogLevelManager()
        self._aggregator = LogAggregator()
        self._logger = LogManager().get_logger(__name__)
        self._cleanup_task: Optional[asyncio.Task] = None
        self._setup_default_levels()

    @property
    def level_manager(self) -> LogLevelManager:
        return self._level_manager

    @property
    def aggregator(self) -> LogAggregator:
        return self._aggregator

    def _setup_default_levels(self) -> None:
        default_level = self._settings.logging.level
        self._level_manager.set_default_level("", default_level)

        for logger_name, level_config in self._settings.logging.loggers.items():
            if isinstance(level_config, dict):
                level = level_config.get("level", default_level)
            else:
                level = level_config
            if isinstance(level, str):
                level = LogLevel(level.upper())
            self._level_manager.set_default_level(logger_name, level)

    async def process_event(self, event: EventMessage) -> ProcessingResult:
        result = ProcessingResult(
            started_at=datetime.utcnow(),
            status=ProcessingStatus.PROCESSING,
        )

        try:
            event_type = event.event_type
            payload = event.payload

            if event_type == "log.level.set":
                set_result = self._handle_set_level(payload)
                result.results = [set_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Log level set successfully"

            elif event_type == "log.level.get":
                get_result = self._handle_get_level(payload)
                result.results = [get_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Log level retrieved"

            elif event_type == "log.rule.add":
                add_result = self._handle_add_rule(payload)
                result.results = [add_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Log rule added successfully"

            elif event_type == "log.rule.remove":
                remove_result = self._handle_remove_rule(payload)
                result.results = [remove_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Log rule removed successfully"

            elif event_type == "log.rules.list":
                list_result = self._handle_list_rules(payload)
                result.results = [list_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Log rules retrieved"

            elif event_type == "log.query":
                query_result = self._handle_query_logs(payload)
                result.results = [query_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Log query completed"

            elif event_type == "log.statistics":
                stats_result = self._handle_get_statistics(payload)
                result.results = [stats_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Log statistics retrieved"

            elif event_type == "log.trace":
                trace_result = self._handle_get_trace_logs(payload)
                result.results = [trace_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Trace logs retrieved"

            elif event_type == "log.errors.summary":
                summary_result = self._handle_get_error_summary(payload)
                result.results = [summary_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Error summary retrieved"

            elif event_type == "log.write":
                write_result = self._handle_write_log(payload)
                result.results = [write_result]
                result.status = ProcessingStatus.SUCCESS
                result.message = "Log written successfully"

            else:
                raise ValidationError(
                    message=f"Unknown event type: {event_type}",
                    suggestion="Check the event type and try again.",
                )

        except Exception as e:
            result.status = ProcessingStatus.FAILED
            result.message = f"Logging event processing failed: {str(e)}"
            result.errors.append({"error": str(e)})

            self._logger.error(
                "Logging event processing failed",
                event_type=event.event_type,
                error=str(e),
            )

        result.completed_at = datetime.utcnow()
        result.calculate_duration()

        return result

    def _handle_set_level(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        logger_name = payload.get("logger_name", "")
        level = payload.get("level")

        if not level:
            raise ValidationError(
                message="Log level is required",
                suggestion="Provide 'level' in the payload.",
            )

        if isinstance(level, str):
            level = LogLevel(level.upper())

        duration = payload.get("duration")

        if duration:
            rule = self._level_manager.set_level_for_duration(
                logger_name=logger_name,
                level=level,
                duration=duration,
                name=payload.get("name"),
            )
            return {
                "rule_id": str(rule.id),
                "logger_name": logger_name,
                "level": level.value,
                "duration": duration,
                "expires_at": rule.expires_at.isoformat() if rule.expires_at else None,
            }
        else:
            self._level_manager.set_default_level(logger_name, level)
            return {
                "logger_name": logger_name,
                "level": level.value,
            }

    def _handle_get_level(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        logger_name = payload.get("logger_name", "")
        message = payload.get("message")

        effective_level = self._level_manager.get_effective_level(logger_name, message)
        default_level = self._level_manager.get_default_level(logger_name)

        return {
            "logger_name": logger_name,
            "default_level": default_level.value,
            "effective_level": effective_level.value,
        }

    def _handle_add_rule(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        name = payload.get("name")
        logger_name = payload.get("logger_name", "")
        level = payload.get("level")
        pattern = payload.get("pattern")
        duration = payload.get("duration")
        priority = payload.get("priority", 0)

        if not name or not level:
            raise ValidationError(
                message="Rule name and level are required",
                suggestion="Provide 'name' and 'level' in the payload.",
            )

        if isinstance(level, str):
            level = LogLevel(level.upper())

        rule = self._level_manager.add_rule(
            name=name,
            logger_name=logger_name,
            level=level,
            pattern=pattern,
            duration=duration,
            priority=priority,
        )

        return {
            "rule_id": str(rule.id),
            "name": rule.name,
            "logger_name": rule.logger_name,
            "level": rule.level.value,
            "pattern": rule.pattern,
            "duration": rule.duration,
            "priority": rule.priority,
            "expires_at": rule.expires_at.isoformat() if rule.expires_at else None,
        }

    def _handle_remove_rule(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        rule_id = payload.get("rule_id")
        if not rule_id:
            raise ValidationError(
                message="Rule ID is required",
                suggestion="Provide 'rule_id' in the payload.",
            )

        rule_uuid = UUID(rule_id)
        success = self._level_manager.remove_rule(rule_uuid)

        return {
            "rule_id": rule_id,
            "removed": success,
        }

    def _handle_list_rules(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        active_only = payload.get("active_only", True)
        rules = self._level_manager.list_rules(active_only=active_only)

        return {
            "total_rules": len(rules),
            "rules": [
                {
                    "id": str(rule.id),
                    "name": rule.name,
                    "logger_name": rule.logger_name,
                    "level": rule.level.value,
                    "pattern": rule.pattern,
                    "duration": rule.duration,
                    "expires_at": rule.expires_at.isoformat() if rule.expires_at else None,
                    "active": rule.active,
                    "priority": rule.priority,
                    "created_at": rule.created_at.isoformat(),
                }
                for rule in rules
            ],
        }

    def _handle_query_logs(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        level = payload.get("level")
        logger_name = payload.get("logger_name")
        start_time = payload.get("start_time")
        end_time = payload.get("end_time")
        trace_id = payload.get("trace_id")
        message_pattern = payload.get("message_pattern")
        limit = payload.get("limit", 100)
        metadata_filter = payload.get("metadata_filter")

        if isinstance(start_time, str):
            start_time = datetime.fromisoformat(start_time)
        if isinstance(end_time, str):
            end_time = datetime.fromisoformat(end_time)

        entries = self._aggregator.get_entries(
            level=level,
            logger_name=logger_name,
            start_time=start_time,
            end_time=end_time,
            trace_id=trace_id,
            message_pattern=message_pattern,
            limit=limit,
            metadata_filter=metadata_filter,
        )

        return {
            "count": len(entries),
            "entries": [e.to_dict() for e in entries],
        }

    def _handle_get_statistics(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        start_time = payload.get("start_time")
        end_time = payload.get("end_time")

        if isinstance(start_time, str):
            start_time = datetime.fromisoformat(start_time)
        if isinstance(end_time, str):
            end_time = datetime.fromisoformat(end_time)

        return self._aggregator.get_statistics(start_time=start_time, end_time=end_time)

    def _handle_get_trace_logs(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        trace_id = payload.get("trace_id")
        if not trace_id:
            raise ValidationError(
                message="Trace ID is required",
                suggestion="Provide 'trace_id' in the payload.",
            )

        return {
            "trace_id": trace_id,
            "logs": self._aggregator.get_trace_logs(trace_id),
        }

    def _handle_get_error_summary(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        limit = payload.get("limit", 10)
        start_time = payload.get("start_time")

        if isinstance(start_time, str):
            start_time = datetime.fromisoformat(start_time)

        return {
            "error_groups": self._aggregator.get_error_summary(limit=limit, start_time=start_time),
        }

    def _handle_write_log(self, payload: Dict[str, Any]) -> Dict[str, Any]:
        level = payload.get("level", "INFO")
        logger_name = payload.get("logger_name", "")
        message = payload.get("message", "")
        metadata = payload.get("metadata")
        trace_id = payload.get("trace_id")
        span_id = payload.get("span_id")

        if not message:
            raise ValidationError(
                message="Log message is required",
                suggestion="Provide 'message' in the payload.",
            )

        entry = self._aggregator.add_entry(
            level=level,
            logger_name=logger_name,
            message=message,
            metadata=metadata,
            trace_id=trace_id,
            span_id=span_id,
        )

        return {
            "entry_id": str(entry.id),
            "timestamp": entry.timestamp.isoformat(),
            "level": entry.level,
            "logger_name": entry.logger_name,
        }

    def set_level(
        self,
        logger_name: str,
        level: LogLevel,
        duration: Optional[int] = None,
    ) -> Dict[str, Any]:
        event = EventMessage(
            event_type="log.level.set",
            payload={
                "logger_name": logger_name,
                "level": level.value if isinstance(level, LogLevel) else level,
                "duration": duration,
            },
            source="logging",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def get_level(self, logger_name: str) -> Dict[str, Any]:
        event = EventMessage(
            event_type="log.level.get",
            payload={"logger_name": logger_name},
            source="logging",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def query_logs(self, **kwargs: Any) -> Dict[str, Any]:
        event = EventMessage(
            event_type="log.query",
            payload=kwargs,
            source="logging",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def get_statistics(self) -> Dict[str, Any]:
        event = EventMessage(
            event_type="log.statistics",
            payload={},
            source="logging",
        )
        result = asyncio.run(self.process_event(event))
        return result.results[0] if result.results else {}

    def search_logs(self, query: str, limit: int = 100) -> List[Dict[str, Any]]:
        return self._aggregator.search(query=query, limit=limit)

    def tail_logs(self, n: int = 50, level: Optional[str] = None) -> List[Dict[str, Any]]:
        return self._aggregator.tail(n=n, level=level)

    def export_logs(self, format: str = "json", **kwargs: Any) -> str:
        return self._aggregator.export_logs(format=format, **kwargs)

    def add_log_listener(self, listener) -> None:
        self._aggregator.add_listener(listener)

    def remove_log_listener(self, listener) -> bool:
        return self._aggregator.remove_listener(listener)

    def get_logger_levels(self) -> Dict[str, Dict[str, Any]]:
        return self._level_manager.get_logger_levels()

    def cleanup_expired_rules(self) -> int:
        return self._level_manager.cleanup_expired_rules()

    def export_rules(self) -> List[Dict[str, Any]]:
        return self._level_manager.export_rules()

    def import_rules(self, rules_data: List[Dict[str, Any]]) -> int:
        return self._level_manager.import_rules(rules_data)

    async def start_cleanup_task(self, interval_seconds: int = 60) -> None:
        async def cleanup_loop():
            while True:
                try:
                    removed = self.cleanup_expired_rules()
                    if removed > 0:
                        self._logger.info(f"Cleaned up {removed} expired log rules")
                except Exception as e:
                    self._logger.error(f"Error in cleanup task: {e}")
                await asyncio.sleep(interval_seconds)

        if self._cleanup_task is None:
            self._cleanup_task = asyncio.create_task(cleanup_loop())

    def stop_cleanup_task(self) -> None:
        if self._cleanup_task:
            self._cleanup_task.cancel()
            self._cleanup_task = None
