import sys
from loguru import logger
from typing import Optional
from config.settings import settings


def configure_logging() -> None:
    logger.remove()

    logger.add(
        sys.stdout,
        level=settings.log_level,
        format="<green>{time:YYYY-MM-DD HH:mm:ss}</green> | "
               "<level>{level: <8}</level> | "
               "<cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> - "
               "<level>{message}</level>",
        enqueue=True,
    )

    logger.add(
        settings.log_file,
        level=settings.log_level,
        rotation="10 MB",
        retention="30 days",
        compression="zip",
        format="{time:YYYY-MM-DD HH:mm:ss} | {level: <8} | {name}:{function}:{line} - {message}",
        enqueue=True,
    )


def get_logger(name: Optional[str] = None):
    if name:
        return logger.bind(name=name)
    return logger


configure_logging()
