from .logger import (
    setup_logger,
    get_logger,
    LogManager,
    RotatingFileHandlerWithArchive,
    JSONFormatter,
)

setup_logging = setup_logger

__all__ = [
    "setup_logger",
    "setup_logging",
    "get_logger",
    "LogManager",
    "RotatingFileHandlerWithArchive",
    "JSONFormatter",
]
