import re
from datetime import datetime
from typing import Optional
from dateutil import parser as date_parser


class LogParser:
    def __init__(self):
        self.level_patterns = [
            r'\[(ERROR|WARN|WARNING|INFO|DEBUG|FATAL|CRITICAL)\]',
            r'\b(ERROR|WARN|WARNING|INFO|DEBUG|FATAL|CRITICAL)\b'
        ]
        self.timestamp_patterns = [
            r'\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:[.,]\d+)?Z?',
            r'\d{2}/\d{2}/\d{4} \d{2}:\d{2}:\d{2}',
            r'\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}'
        ]

    def parse(self, raw_log: str, default_level: str = 'info') -> tuple:
        log_level = self._extract_level(raw_log, default_level)
        timestamp = self._extract_timestamp(raw_log)
        log_content = raw_log.strip()
        log_source = 'application'
        return log_level, log_content, timestamp, log_source

    def _extract_level(self, raw_log: str, default_level: str) -> str:
        for pattern in self.level_patterns:
            match = re.search(pattern, raw_log, re.IGNORECASE)
            if match:
                level = match.group(1).lower()
                if level == 'warn':
                    level = 'warning'
                if level == 'critical':
                    level = 'fatal'
                return level
        return default_level

    def _extract_timestamp(self, raw_log: str) -> Optional[datetime]:
        for pattern in self.timestamp_patterns:
            match = re.search(pattern, raw_log)
            if match:
                try:
                    ts_str = match.group(0)
                    return date_parser.parse(ts_str)
                except (ValueError, TypeError):
                    continue
        return None
