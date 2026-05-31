import sys
from loguru import logger
from .config import settings


def get_logger(name: str = "llm_gateway"):
    logger.remove()

    log_format = (
        "<green>{time:YYYY-MM-DD HH:mm:ss.SSS}</green> | "
        "<level>{level: <8}</level> | "
        "<cyan>{name}</cyan>:<cyan>{function}</cyan>:<cyan>{line}</cyan> | "
        "<level>{message}</level>"
    )

    logger.add(
        sys.stdout,
        format=log_format,
        level=settings.LOG_LEVEL,
        enqueue=True,
        backtrace=settings.is_development,
        diagnose=settings.is_development,
    )

    logger.add(
        f"logs/{name}.log",
        format=log_format,
        level="INFO",
        rotation="100 MB",
        retention="30 days",
        compression="zip",
        enqueue=True,
    )

    logger.add(
        f"logs/{name}_error.log",
        format=log_format,
        level="ERROR",
        rotation="100 MB",
        retention="30 days",
        compression="zip",
        enqueue=True,
    )

    return logger
