import logging
import sys
from typing import Any, Dict, Optional
import structlog
from structlog.types import EventDict, Processor


def add_request_id(_: Any, __: str, event_dict: EventDict) -> EventDict:
    return event_dict


def add_timestamp(_: Any, __: str, event_dict: EventDict) -> EventDict:
    from datetime import datetime, timezone
    event_dict["timestamp"] = datetime.now(timezone.utc).isoformat()
    return event_dict


def setup_logging(log_level: str = "info", json_format: bool = True) -> None:
    level = getattr(logging, log_level.upper(), logging.INFO)

    shared_processors: list[Processor] = [
        structlog.contextvars.merge_contextvars,
        structlog.processors.add_log_level,
        add_timestamp,
        structlog.processors.StackInfoRenderer(),
        structlog.processors.format_exc_info,
    ]

    if json_format:
        processors = shared_processors + [
            structlog.processors.dict_tracebacks,
            structlog.processors.JSONRenderer(),
        ]
    else:
        processors = shared_processors + [
            structlog.dev.ConsoleRenderer(),
        ]

    structlog.configure(
        processors=processors,
        wrapper_class=structlog.make_filtering_bound_logger(level),
        context_class=dict,
        logger_factory=structlog.PrintLoggerFactory(),
        cache_logger_on_first_use=True,
    )

    logging.basicConfig(
        format="%(message)s",
        stream=sys.stdout,
        level=level,
    )

    for logger_name in ["uvicorn", "uvicorn.access", "uvicorn.error"]:
        logging_logger = logging.getLogger(logger_name)
        logging_logger.handlers = [structlog.stdlib.ProcessorFormatter(
            processor=structlog.dev.ConsoleRenderer() if not json_format else structlog.processors.JSONRenderer(),
        )]
        logging_logger.propagate = False


def get_logger(name: Optional[str] = None) -> Any:
    return structlog.get_logger(name or "api-gateway")
