import re
import json
from datetime import datetime
from typing import Dict, List, Optional, Any, Iterator
from dataclasses import dataclass, field
from enum import Enum


class LogLevel(Enum):
    DEBUG = "DEBUG"
    INFO = "INFO"
    WARNING = "WARNING"
    ERROR = "ERROR"
    CRITICAL = "CRITICAL"
    UNKNOWN = "UNKNOWN"


@dataclass
class LogEntry:
    log_id: str
    timestamp: Optional[datetime]
    level: LogLevel
    source: str
    message: str
    stack_trace: Optional[str] = None
    fields: Dict[str, Any] = field(default_factory=dict)
    raw_line: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {
            "log_id": self.log_id,
            "timestamp": self.timestamp.isoformat() if self.timestamp else None,
            "level": self.level.value,
            "source": self.source,
            "message": self.message,
            "stack_trace": self.stack_trace,
            "fields": self.fields
        }


class BaseParser:
    def __init__(self, config: Optional[Dict[str, Any]] = None):
        self.config = config or {}
        self.log_counter = 0

    def generate_log_id(self) -> str:
        self.log_counter += 1
        return f"log_{self.log_counter:06d}"

    def parse_level(self, level_str: str) -> LogLevel:
        level_str = level_str.upper().strip()
        level_mapping = {
            "DEBUG": LogLevel.DEBUG,
            "INFO": LogLevel.INFO,
            "INFORMATION": LogLevel.INFO,
            "WARN": LogLevel.WARNING,
            "WARNING": LogLevel.WARNING,
            "ERR": LogLevel.ERROR,
            "ERROR": LogLevel.ERROR,
            "FATAL": LogLevel.CRITICAL,
            "CRITICAL": LogLevel.CRITICAL
        }
        return level_mapping.get(level_str, LogLevel.UNKNOWN)

    def parse(self, line: str) -> Optional[LogEntry]:
        raise NotImplementedError("Subclasses must implement parse method")


class JSONParser(BaseParser):
    def __init__(self, config: Optional[Dict[str, Any]] = None):
        super().__init__(config)
        self.timestamp_field = self.config.get("timestamp_field", "timestamp")
        self.level_field = self.config.get("level_field", "level")
        self.message_field = self.config.get("message_field", "message")
        self.source_field = self.config.get("source_field", "source")
        self.stack_trace_field = self.config.get("stack_trace_field", "stack_trace")

    def parse(self, line: str) -> Optional[LogEntry]:
        try:
            data = json.loads(line.strip())
        except json.JSONDecodeError:
            return None

        log_id = self.generate_log_id()
        
        timestamp_str = data.get(self.timestamp_field, "")
        timestamp = self._parse_timestamp(timestamp_str)
        
        level_str = data.get(self.level_field, "UNKNOWN")
        level = self.parse_level(level_str)
        
        source = data.get(self.source_field, "unknown")
        message = data.get(self.message_field, "")
        stack_trace = data.get(self.stack_trace_field)
        
        fields = {k: v for k, v in data.items() 
                  if k not in [self.timestamp_field, self.level_field, 
                              self.message_field, self.source_field, 
                              self.stack_trace_field]}

        return LogEntry(
            log_id=log_id,
            timestamp=timestamp,
            level=level,
            source=source,
            message=message,
            stack_trace=stack_trace,
            fields=fields,
            raw_line=line
        )

    def _parse_timestamp(self, ts_str: str) -> Optional[datetime]:
        if not ts_str:
            return None
        
        formats = [
            "%Y-%m-%dT%H:%M:%S.%fZ",
            "%Y-%m-%dT%H:%M:%SZ",
            "%Y-%m-%d %H:%M:%S.%f",
            "%Y-%m-%d %H:%M:%S",
            "%d/%b/%Y:%H:%M:%S %z",
        ]
        
        for fmt in formats:
            try:
                return datetime.strptime(ts_str, fmt)
            except (ValueError, TypeError):
                continue
        
        try:
            from dateutil import parser as dateutil_parser
            return dateutil_parser.parse(ts_str)
        except (ImportError, ValueError, TypeError):
            pass
        
        return None


class TextParser(BaseParser):
    def __init__(self, config: Optional[Dict[str, Any]] = None):
        super().__init__(config)
        self.format_pattern = self.config.get("format_pattern", None)
        self.timestamp_format = self.config.get("timestamp_format", None)
        
        if not self.format_pattern:
            self.format_pattern = (
                r"(?P<timestamp>\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})?)"
                r"\s+"
                r"(?P<level>DEBUG|INFO|WARN|WARNING|ERROR|ERR|FATAL|CRITICAL)"
                r"\s+"
                r"(?P<source>\S+)"
                r"\s*"
                r"(?P<message>.*)$"
            )
        
        self.compiled_pattern = re.compile(self.format_pattern, re.IGNORECASE)

    def parse(self, line: str) -> Optional[LogEntry]:
        line = line.rstrip("\n")
        if not line:
            return None

        match = self.compiled_pattern.match(line)
        if not match:
            return self._parse_fallback(line)

        groups = match.groupdict()
        
        log_id = self.generate_log_id()
        timestamp_str = groups.get("timestamp", "")
        timestamp = self._parse_timestamp(timestamp_str)
        
        level_str = groups.get("level", "UNKNOWN")
        level = self.parse_level(level_str)
        
        source = groups.get("source", "unknown")
        message = groups.get("message", "")

        return LogEntry(
            log_id=log_id,
            timestamp=timestamp,
            level=level,
            source=source,
            message=message,
            raw_line=line
        )

    def _parse_fallback(self, line: str) -> Optional[LogEntry]:
        level_patterns = [
            (r"\b(DEBUG)\b", LogLevel.DEBUG),
            (r"\b(INFO|INFORMATION)\b", LogLevel.INFO),
            (r"\b(WARN|WARNING)\b", LogLevel.WARNING),
            (r"\b(ERROR|ERR)\b", LogLevel.ERROR),
            (r"\b(FATAL|CRITICAL)\b", LogLevel.CRITICAL),
        ]

        level = LogLevel.UNKNOWN
        for pattern, lvl in level_patterns:
            match = re.search(pattern, line, re.IGNORECASE)
            if match:
                level = lvl
                break

        return LogEntry(
            log_id=self.generate_log_id(),
            timestamp=None,
            level=level,
            source="unknown",
            message=line,
            raw_line=line
        )

    def _parse_timestamp(self, ts_str: str) -> Optional[datetime]:
        if not ts_str:
            return None

        if self.timestamp_format:
            try:
                return datetime.strptime(ts_str, self.timestamp_format)
            except (ValueError, TypeError):
                pass

        formats = [
            "%Y-%m-%dT%H:%M:%S.%fZ",
            "%Y-%m-%dT%H:%M:%SZ",
            "%Y-%m-%d %H:%M:%S.%f",
            "%Y-%m-%d %H:%M:%S",
            "%Y/%m/%d %H:%M:%S",
            "%d-%b-%Y %H:%M:%S",
        ]
        
        for fmt in formats:
            try:
                return datetime.strptime(ts_str, fmt)
            except (ValueError, TypeError):
                continue
        
        try:
            from dateutil import parser as dateutil_parser
            return dateutil_parser.parse(ts_str)
        except (ImportError, ValueError, TypeError):
            pass
        
        return None


class SyslogParser(BaseParser):
    SYSLOG_PATTERN = re.compile(
        r"(?P<priority><\d+>)?"
        r"(?P<timestamp>\w{3}\s+\d{1,2}\s+\d{2}:\d{2}:\d{2})?"
        r"\s*"
        r"(?P<hostname>\S+)?"
        r"\s*"
        r"(?P<source>\S+?)(?:\[\d+\])?:"
        r"\s*"
        r"(?P<message>.*)$"
    )

    def __init__(self, config: Optional[Dict[str, Any]] = None):
        super().__init__(config)

    def parse(self, line: str) -> Optional[LogEntry]:
        line = line.rstrip("\n")
        if not line:
            return None

        match = self.SYSLOG_PATTERN.match(line)
        if not match:
            return self._parse_fallback(line)

        groups = match.groupdict()
        
        log_id = self.generate_log_id()
        
        priority = groups.get("priority", "")
        level = self._extract_level_from_priority(priority)
        
        timestamp_str = groups.get("timestamp", "")
        timestamp = self._parse_timestamp(timestamp_str)
        
        source = groups.get("source", "unknown")
        message = groups.get("message", "")

        fields = {}
        hostname = groups.get("hostname")
        if hostname:
            fields["hostname"] = hostname

        return LogEntry(
            log_id=log_id,
            timestamp=timestamp,
            level=level,
            source=source,
            message=message,
            fields=fields,
            raw_line=line
        )

    def _extract_level_from_priority(self, priority: str) -> LogLevel:
        if not priority:
            return LogLevel.INFO
        
        match = re.search(r"<(\d+)>", priority)
        if match:
            prio = int(match.group(1))
            severity = prio & 7
            
            level_mapping = {
                0: LogLevel.CRITICAL,
                1: LogLevel.CRITICAL,
                2: LogLevel.CRITICAL,
                3: LogLevel.ERROR,
                4: LogLevel.WARNING,
                5: LogLevel.INFO,
                6: LogLevel.INFO,
                7: LogLevel.DEBUG,
            }
            return level_mapping.get(severity, LogLevel.INFO)
        
        return LogLevel.INFO

    def _parse_timestamp(self, ts_str: str) -> Optional[datetime]:
        if not ts_str:
            return None

        now = datetime.now()
        year = now.year
        
        try:
            ts = datetime.strptime(f"{year} {ts_str}", "%Y %b %d %H:%M:%S")
            if ts > now:
                ts = ts.replace(year=year - 1)
            return ts
        except (ValueError, TypeError):
            pass

        return None

    def _parse_fallback(self, line: str) -> Optional[LogEntry]:
        level = LogLevel.INFO
        
        level_keywords = [
            (r"error|exception|failed", LogLevel.ERROR),
            (r"warning|warn", LogLevel.WARNING),
            (r"debug", LogLevel.DEBUG),
            (r"critical|fatal", LogLevel.CRITICAL),
        ]
        
        for pattern, lvl in level_keywords:
            if re.search(pattern, line, re.IGNORECASE):
                level = lvl
                break

        return LogEntry(
            log_id=self.generate_log_id(),
            timestamp=None,
            level=level,
            source="unknown",
            message=line,
            raw_line=line
        )


class LogParser:
    PARSER_MAPPING = {
        "json": JSONParser,
        "text": TextParser,
        "syslog": SyslogParser,
    }

    def __init__(self, format: str = "auto", config: Optional[Dict[str, Any]] = None):
        self.format = format
        self.config = config or {}
        self.current_parser: Optional[BaseParser] = None

    def detect_format(self, sample_lines: List[str]) -> str:
        if not sample_lines:
            return "text"

        json_count = 0
        syslog_count = 0
        
        for line in sample_lines[:10]:
            line = line.strip()
            if not line:
                continue
            
            if line.startswith("{") and line.endswith("}"):
                try:
                    json.loads(line)
                    json_count += 1
                except json.JSONDecodeError:
                    pass
            
            if SyslogParser.SYSLOG_PATTERN.match(line):
                syslog_count += 1

        if json_count >= len(sample_lines) * 0.5:
            return "json"
        if syslog_count >= len(sample_lines) * 0.3:
            return "syslog"
        
        return "text"

    def get_parser(self, format: str) -> BaseParser:
        parser_class = self.PARSER_MAPPING.get(format.lower(), TextParser)
        return parser_class(self.config)

    def parse_file(self, filepath: str, encoding: str = "utf-8") -> Iterator[LogEntry]:
        sample_lines = []
        try:
            with open(filepath, "r", encoding=encoding, errors="ignore") as f:
                for _ in range(20):
                    line = f.readline()
                    if not line:
                        break
                    sample_lines.append(line)
        except (IOError, OSError):
            pass

        if self.format == "auto":
            detected_format = self.detect_format(sample_lines)
            self.current_parser = self.get_parser(detected_format)
        else:
            self.current_parser = self.get_parser(self.format)

        with open(filepath, "r", encoding=encoding, errors="ignore") as f:
            for line in f:
                entry = self.current_parser.parse(line)
                if entry:
                    yield entry

    def parse_lines(self, lines: List[str]) -> Iterator[LogEntry]:
        if self.format == "auto":
            detected_format = self.detect_format(lines[:20])
            self.current_parser = self.get_parser(detected_format)
        else:
            self.current_parser = self.get_parser(self.format)

        for line in lines:
            entry = self.current_parser.parse(line)
            if entry:
                yield entry
