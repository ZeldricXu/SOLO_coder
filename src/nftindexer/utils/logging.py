import sys
from typing import Optional
from loguru import logger

from ..config import get_settings, LoggingSettings

_logger_configured = False


def setup_logging(settings: Optional[LoggingSettings] = None) -> None:
    global _logger_configured
    if _logger_configured:
        return

    if settings is None:
        settings = get_settings().logging

    logger.remove()

    log_format = (
        "<green>{time:YYYY-MM-DD HH:mm:ss.SSS}</green> | "
        "<level>{level: <8}</level> | "
        "<cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> - "
        "<level>{message}</level>"
    )

    logger.add(
        sys.stdout,
        level=settings.level,
        format=log_format,
        enqueue=True,
    )

    if settings.file_path:
        logger.add(
            settings.file_path,
            level=settings.level,
            format=log_format,
            rotation=settings.rotation,
            retention=settings.retention,
            compression=settings.compression,
            enqueue=True,
        )

    _logger_configured = True


def get_logger(name: Optional[str] = None):
    if not _logger_configured:
        setup_logging()
    if name:
        return logger.bind(module=name)
    return logger
