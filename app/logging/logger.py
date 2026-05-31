"""
Context-aware logger.
"""

import logging
from typing import Any, Dict, Optional


class ContextLogger:
    def __init__(self, name: str, trace_id: Optional[str] = None):
        self.name = name
        self.logger = logging.getLogger(name)
        self.trace_id = trace_id
        self.context: Dict[str, Any] = {}
    
    def bind(self, **kwargs: Any) -> "ContextLogger":
        self.context.update(kwargs)
        return self
    
    def _log(self, level: int, message: str, extra: Optional[Dict[str, Any]] = None):
        log_extra = {**self.context}
        if self.trace_id:
            log_extra["trace_id"] = self.trace_id
        if extra:
            log_extra.update(extra)
        
        self.logger.log(
            level,
            message,
            extra={"extra": log_extra}
        )
    
    def debug(self, message: str, **kwargs: Any):
        self._log(logging.DEBUG, message, kwargs)
    
    def info(self, message: str, **kwargs: Any):
        self._log(logging.INFO, message, kwargs)
    
    def warning(self, message: str, **kwargs: Any):
        self._log(logging.WARNING, message, kwargs)
    
    def warn(self, message: str, **kwargs: Any):
        self.warning(message, **kwargs)
    
    def error(self, message: str, **kwargs: Any):
        self._log(logging.ERROR, message, kwargs)
    
    def critical(self, message: str, **kwargs: Any):
        self._log(logging.CRITICAL, message, kwargs)
    
    def exception(self, message: str, **kwargs: Any):
        self.logger.exception(
            message,
            extra={"extra": {**self.context, "trace_id": self.trace_id, **kwargs}}
        )
