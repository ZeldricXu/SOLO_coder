"""Log level manager for logging module."""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Callable, Dict, List, Optional, Pattern
from uuid import UUID, uuid4

from ...domain.errors.common import ValidationError
from ...infrastructure.logging.structured_logger import LogManager
from ...infrastructure.config.settings import LogLevel


@dataclass
class LogLevelRule:
    id: UUID = field(default_factory=uuid4)
    name: str
    logger_name: str
    level: LogLevel
    pattern: Optional[str] = None
    compiled_pattern: Optional[Pattern] = None
    duration: Optional[int] = None
    expires_at: Optional[datetime] = None
    created_at: datetime = field(default_factory=datetime.utcnow)
    active: bool = True
    priority: int = 0

    def matches(self, logger_name: str, message: Optional[str] = None) -> bool:
        if not self.active:
            return False

        if self.expires_at and datetime.utcnow() > self.expires_at:
            self.active = False
            return False

        if not logger_name.startswith(self.logger_name) and logger_name != self.logger_name:
            return False

        if self.compiled_pattern and message:
            return bool(self.compiled_pattern.search(message))

        return True


class LogLevelManager:
    def __init__(self) -> None:
        self._logger = LogManager().get_logger(__name__)
        self._rules: Dict[UUID, LogLevelRule] = {}
        self._default_levels: Dict[str, LogLevel] = {}
        self._loggers: Dict[str, logging.Logger] = {}
        self._callbacks: List[Callable[[str, LogLevel, LogLevel], None]] = []

    def set_default_level(self, logger_name: str, level: LogLevel) -> None:
        self._default_levels[logger_name] = level
        self._apply_level(logger_name, level)
        self._logger.info(f"Set default log level for {logger_name} to {level.value}")

    def get_default_level(self, logger_name: str) -> LogLevel:
        prefix = logger_name
        while prefix:
            if prefix in self._default_levels:
                return self._default_levels[prefix]
            if "." in prefix:
                prefix = prefix.rsplit(".", 1)[0]
            else:
                break
        return self._default_levels.get("", LogLevel.INFO)

    def add_rule(
        self,
        name: str,
        logger_name: str,
        level: LogLevel,
        pattern: Optional[str] = None,
        duration: Optional[int] = None,
        priority: int = 0,
    ) -> LogLevelRule:
        expires_at = None
        if duration:
            expires_at = datetime.utcnow() + timedelta(seconds=duration)

        compiled_pattern = None
        if pattern:
            try:
                compiled_pattern = re.compile(pattern)
            except re.error as e:
                raise ValidationError(
                    message=f"Invalid regex pattern: {e}",
                    suggestion="Check the regex pattern syntax.",
                )

        rule = LogLevelRule(
            name=name,
            logger_name=logger_name,
            level=level,
            pattern=pattern,
            compiled_pattern=compiled_pattern,
            duration=duration,
            expires_at=expires_at,
            priority=priority,
        )

        self._rules[rule.id] = rule
        self._logger.info(
            f"Added log level rule: {name}",
            logger_name=logger_name,
            level=level.value,
            duration=duration,
        )

        self._apply_rules()
        return rule

    def remove_rule(self, rule_id: UUID) -> bool:
        if rule_id in self._rules:
            rule = self._rules.pop(rule_id)
            self._logger.info(f"Removed log level rule: {rule.name}")
            self._apply_rules()
            return True
        return False

    def get_rule(self, rule_id: UUID) -> Optional[LogLevelRule]:
        return self._rules.get(rule_id)

    def list_rules(self, active_only: bool = True) -> List[LogLevelRule]:
        rules = list(self._rules.values())
        if active_only:
            rules = [r for r in rules if r.active]
        return sorted(rules, key=lambda r: (-r.priority, r.created_at))

    def enable_rule(self, rule_id: UUID) -> bool:
        rule = self._rules.get(rule_id)
        if not rule:
            return False
        rule.active = True
        self._apply_rules()
        return True

    def disable_rule(self, rule_id: UUID) -> bool:
        rule = self._rules.get(rule_id)
        if not rule:
            return False
        rule.active = False
        self._apply_rules()
        return True

    def get_effective_level(self, logger_name: str, message: Optional[str] = None) -> LogLevel:
        active_rules = [
            rule for rule in self._rules.values()
            if rule.matches(logger_name, message)
        ]

        if active_rules:
            active_rules.sort(key=lambda r: (-r.priority, r.created_at))
            return active_rules[0].level

        return self.get_default_level(logger_name)

    def _apply_rules(self) -> None:
        logger_names = set(self._default_levels.keys())
        for rule in self._rules.values():
            if rule.active:
                logger_names.add(rule.logger_name)

        for logger_name in logger_names:
            effective_level = self.get_effective_level(logger_name)
            self._apply_level(logger_name, effective_level)

    def _apply_level(self, logger_name: str, level: LogLevel) -> None:
        if logger_name:
            logger = logging.getLogger(logger_name)
        else:
            logger = logging.getLogger()

        old_level = logger.getEffectiveLevel()
        new_level = self._to_python_level(level)

        if old_level != new_level:
            logger.setLevel(new_level)
            self._notify_callbacks(logger_name, self._from_python_level(old_level), level)

    def _to_python_level(self, level: LogLevel) -> int:
        level_map = {
            LogLevel.DEBUG: logging.DEBUG,
            LogLevel.INFO: logging.INFO,
            LogLevel.WARNING: logging.WARNING,
            LogLevel.ERROR: logging.ERROR,
            LogLevel.CRITICAL: logging.CRITICAL,
        }
        return level_map.get(level, logging.INFO)

    def _from_python_level(self, level: int) -> LogLevel:
        level_map = {
            logging.DEBUG: LogLevel.DEBUG,
            logging.INFO: LogLevel.INFO,
            logging.WARNING: LogLevel.WARNING,
            logging.ERROR: LogLevel.ERROR,
            logging.CRITICAL: LogLevel.CRITICAL,
        }
        return level_map.get(level, LogLevel.INFO)

    def add_level_change_callback(
        self,
        callback: Callable[[str, LogLevel, LogLevel], None],
    ) -> None:
        self._callbacks.append(callback)

    def remove_level_change_callback(
        self,
        callback: Callable[[str, LogLevel, LogLevel], None],
    ) -> bool:
        if callback in self._callbacks:
            self._callbacks.remove(callback)
            return True
        return False

    def _notify_callbacks(
        self,
        logger_name: str,
        old_level: LogLevel,
        new_level: LogLevel,
    ) -> None:
        for callback in self._callbacks:
            try:
                callback(logger_name, old_level, new_level)
            except Exception as e:
                self._logger.error(f"Error in log level change callback: {e}")

    def cleanup_expired_rules(self) -> int:
        now = datetime.utcnow()
        expired_ids = [
            rule_id for rule_id, rule in self._rules.items()
            if rule.expires_at and now > rule.expires_at
        ]

        for rule_id in expired_ids:
            self.remove_rule(rule_id)

        if expired_ids:
            self._apply_rules()

        return len(expired_ids)

    def get_logger_levels(self) -> Dict[str, Dict[str, Any]]:
        result = {}

        for rule in self._rules.values():
            if rule.logger_name not in result:
                result[rule.logger_name] = {
                    "default_level": self.get_default_level(rule.logger_name).value,
                    "effective_level": self.get_effective_level(rule.logger_name).value,
                    "rules": [],
                }
            result[rule.logger_name]["rules"].append({
                "id": str(rule.id),
                "name": rule.name,
                "level": rule.level.value,
                "pattern": rule.pattern,
                "active": rule.active,
                "expires_at": rule.expires_at.isoformat() if rule.expires_at else None,
                "priority": rule.priority,
            })

        for logger_name, level in self._default_levels.items():
            if logger_name not in result:
                result[logger_name] = {
                    "default_level": level.value,
                    "effective_level": self.get_effective_level(logger_name).value,
                    "rules": [],
                }

        return result

    def set_level_for_duration(
        self,
        logger_name: str,
        level: LogLevel,
        duration: int,
        name: Optional[str] = None,
    ) -> LogLevelRule:
        return self.add_rule(
            name=name or f"temp_{logger_name}_{level.value}",
            logger_name=logger_name,
            level=level,
            duration=duration,
            priority=100,
        )

    def reset_level(self, logger_name: str) -> bool:
        rules_to_remove = [
            rule_id for rule_id, rule in self._rules.items()
            if rule.logger_name == logger_name
        ]

        for rule_id in rules_to_remove:
            self.remove_rule(rule_id)

        default_level = self.get_default_level(logger_name)
        self._apply_level(logger_name, default_level)

        return len(rules_to_remove) > 0

    def export_rules(self) -> List[Dict[str, Any]]:
        return [
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
            for rule in self._rules.values()
        ]

    def import_rules(self, rules_data: List[Dict[str, Any]]) -> int:
        imported = 0
        for rule_data in rules_data:
            try:
                self.add_rule(
                    name=rule_data["name"],
                    logger_name=rule_data["logger_name"],
                    level=LogLevel(rule_data["level"]),
                    pattern=rule_data.get("pattern"),
                    duration=rule_data.get("duration"),
                    priority=rule_data.get("priority", 0),
                )
                imported += 1
            except Exception as e:
                self._logger.error(f"Failed to import rule: {e}")

        return imported
