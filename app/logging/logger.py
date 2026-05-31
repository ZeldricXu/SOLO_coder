import logging
import sys
import json
import time
import uuid
import traceback
from datetime import datetime, timezone
from typing import Any, Dict, Optional, ContextManager, Union, TypeVar
from contextvars import ContextVar
from pythonjsonlogger import jsonlogger
from contextlib import contextmanager

from app.config import settings
from app.exceptions import PlatformException

_request_id_var: ContextVar[Optional[str]] = ContextVar("request_id", default=None)
_user_id_var: ContextVar[Optional[str]] = ContextVar("user_id", default=None)
_extra_context_var: ContextVar[Dict[str, Any]] = ContextVar(
    "extra_context", default={}
)

T = TypeVar("T")


class StructuredJsonFormatter(jsonlogger.JsonFormatter):
    def add_fields(
        self, log_record: Dict[str, Any], record: logging.LogRecord, message_dict: Dict[str, Any]
    ) -> None:
        super().add_fields(log_record, record, message_dict)

        log_record["timestamp"] = datetime.fromtimestamp(
            record.created, tz=timezone.utc
        ).isoformat()

        log_record["level"] = record.levelname
        log_record["logger"] = record.name
        log_record["module"] = record.module
        log_record["function"] = record.funcName
        log_record["line_number"] = record.lineno

        log_record["process_id"] = record.process
        log_record["thread_id"] = record.thread

        request_id = _request_id_var.get()
        if request_id:
            log_record["request_id"] = request_id

        user_id = _user_id_var.get()
        if user_id:
            log_record["user_id"] = user_id

        extra_context = _extra_context_var.get()
        if extra_context:
            log_record.update(extra_context)

        if record.exc_info and "exception" not in log_record:
            exc_type, exc_value, _ = record.exc_info
            log_record["exception"] = self._format_exception_structured(exc_type, exc_value)

        if "message" not in log_record:
            log_record["message"] = record.getMessage()

    def _format_exception_structured(
        self, exc_type: type, exc_value: BaseException
    ) -> Dict[str, Any]:
        result = {
            "type": exc_type.__name__,
            "module": exc_type.__module__,
            "message": str(exc_value),
            "traceback": traceback.format_exc(),
        }

        if isinstance(exc_value, PlatformException):
            result["error_id"] = exc_value.error_id
            result["error_code"] = exc_value.error_code
            result["http_status"] = exc_value.code
            result["details"] = exc_value.details

        cause = getattr(exc_value, "__cause__", None)
        if cause is not None:
            result["cause"] = self._format_exception_structured(type(cause), cause)

        context = getattr(exc_value, "__context__", None)
        if context is not None and context is not cause:
            result["context"] = self._format_exception_structured(type(context), context)

        return result


class StructuredLogger:
    def __init__(self, name: str, level: str = "INFO"):
        self.name = name
        self._logger = logging.getLogger(name)
        self._logger.setLevel(getattr(logging, level.upper(), logging.INFO))
        self._logger.propagate = False

        if not self._logger.handlers:
            self._setup_handlers()

    def _setup_handlers(self) -> None:
        handler = logging.StreamHandler(sys.stdout)
        formatter = StructuredJsonFormatter(
            "%(message)s",
            timestamp=True,
        )
        handler.setFormatter(formatter)
        self._logger.addHandler(handler)

    def _extract_exception_context(
        self, exc_info: Optional[BaseException], **kwargs: Any
    ) -> Dict[str, Any]:
        context = {}
        for key, value in kwargs.items():
            if key in _RESERVED_LOGRECORD_FIELDS:
                context[f"_{key}"] = value
            else:
                context[key] = value

        if exc_info is not None and isinstance(exc_info, PlatformException):
            context["error_id"] = exc_info.error_id
            context["error_code"] = exc_info.error_code
            context["http_status"] = exc_info.code

            for key, value in exc_info.details.items():
                safe_key = f"_{key}" if key in _RESERVED_LOGRECORD_FIELDS else key
                if safe_key not in context:
                    context[safe_key] = value

        return context

    def _log(
        self,
        level: str,
        message: str,
        exc_info: Optional[Union[BaseException, bool]] = None,
        **kwargs: Any,
    ) -> None:
        log_method = getattr(self._logger, level.lower(), self._logger.info)
        context = self._extract_exception_context(exc_info if isinstance(exc_info, BaseException) else None, **kwargs)

        if exc_info:
            if isinstance(exc_info, BaseException):
                log_method(message, exc_info=exc_info, extra=context)
            else:
                log_method(message, exc_info=True, extra=context)
        else:
            log_method(message, extra=context)

    def debug(self, message: str, **kwargs: Any) -> None:
        self._log("DEBUG", message, **kwargs)

    def info(self, message: str, **kwargs: Any) -> None:
        self._log("INFO", message, **kwargs)

    def warning(self, message: str, **kwargs: Any) -> None:
        self._log("WARNING", message, **kwargs)

    def warn(self, message: str, **kwargs: Any) -> None:
        self.warning(message, **kwargs)

    def error(
        self,
        message: str,
        exc_info: Optional[Union[BaseException, bool]] = None,
        **kwargs: Any,
    ) -> None:
        self._log("ERROR", message, exc_info=exc_info, **kwargs)

    def critical(
        self,
        message: str,
        exc_info: Optional[Union[BaseException, bool]] = None,
        **kwargs: Any,
    ) -> None:
        self._log("CRITICAL", message, exc_info=exc_info, **kwargs)

    def exception(
        self,
        message: str,
        exc: Optional[BaseException] = None,
        **kwargs: Any,
    ) -> None:
        self.error(message, exc_info=exc or True, **kwargs)

    @contextmanager
    def bind(self, **kwargs: Any) -> ContextManager[None]:
        token = _extra_context_var.set({**_extra_context_var.get(), **kwargs})
        try:
            yield
        finally:
            _extra_context_var.reset(token)

    @contextmanager
    def error_boundary(
        self,
        operation: str,
        reraise: bool = True,
        default_return: Optional[T] = None,
        **context: Any,
    ) -> ContextManager[Optional[T]]:
        start_time = time.time()
        try:
            with self.bind(operation=operation, **context):
                self.debug(f"Starting operation: {operation}")
                yield default_return
                duration = (time.time() - start_time) * 1000
                self.debug(
                    f"Operation completed: {operation}",
                    duration_ms=round(duration, 2),
                    status="success",
                )
        except Exception as e:
            duration = (time.time() - start_time) * 1000
            self.error(
                f"Operation failed: {operation}",
                exc_info=e,
                duration_ms=round(duration, 2),
                status="failed",
                error_type=type(e).__name__,
            )
            if reraise:
                raise
            return default_return


class LogContext:
    @staticmethod
    def set_request_id(request_id: Optional[str] = None) -> str:
        rid = request_id or str(uuid.uuid4())
        _request_id_var.set(rid)
        return rid

    @staticmethod
    def get_request_id() -> Optional[str]:
        return _request_id_var.get()

    @staticmethod
    def set_user_id(user_id: str) -> None:
        _user_id_var.set(user_id)

    @staticmethod
    def get_user_id() -> Optional[str]:
        return _user_id_var.get()

    @staticmethod
    @contextmanager
    def bind(**kwargs: Any) -> ContextManager[None]:
        token = _extra_context_var.set({**_extra_context_var.get(), **kwargs})
        try:
            yield
        finally:
            _extra_context_var.reset(token)

    @staticmethod
    def clear() -> None:
        _request_id_var.set(None)
        _user_id_var.set(None)
        _extra_context_var.set({})


_loggers: Dict[str, StructuredLogger] = {}

_RESERVED_LOGRECORD_FIELDS = {
    "name", "msg", "args", "levelname", "levelno", "pathname", "filename",
    "module", "lineno", "funcName", "created", "msecs", "relativeCreated",
    "thread", "threadName", "process", "processName", "exc_info", "exc_text",
    "stack_info", "message", "asctime",
}


def get_logger(name: str) -> StructuredLogger:
    if name not in _loggers:
        _loggers[name] = StructuredLogger(name, level=settings.log_level)
    return _loggers[name]


def setup_logging() -> None:
    root_logger = logging.getLogger()
    root_logger.setLevel(getattr(logging, settings.log_level.upper(), logging.INFO))

    handler = logging.StreamHandler(sys.stdout)
    formatter = StructuredJsonFormatter("%(message)s", timestamp=True)
    handler.setFormatter(formatter)

    root_logger.handlers.clear()
    root_logger.addHandler(handler)

    get_logger("app").info(
        "Structured logging initialized",
        log_level=settings.log_level,
        environment=settings.environment,
    )


@contextmanager
def log_operation(
    logger_name: str,
    operation: str,
    reraise: bool = True,
    **context: Any,
) -> ContextManager[None]:
    logger = get_logger(logger_name)
    with logger.error_boundary(operation=operation, reraise=reraise, **context):
        yield
