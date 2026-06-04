import logging
import logging.config
import os
from pythonjsonlogger import jsonlogger


def setup_logging(log_level: str = "INFO", debug: bool = False):
    log_format = (
        "%(asctime)s %(name)s %(levelname)s "
        "%(module)s:%(lineno)d %(message)s"
    )

    json_format = (
        "%(asctime)s %(levelname)s %(name)s "
        "%(module)s %(lineno)d %(message)s"
    )

    handlers = {
        "default": {
            "level": log_level,
            "formatter": "json" if not debug else "standard",
            "class": "logging.StreamHandler",
            "stream": "ext://sys.stdout",
        },
        "error": {
            "level": "ERROR",
            "formatter": "json" if not debug else "standard",
            "class": "logging.StreamHandler",
            "stream": "ext://sys.stderr",
        },
    }

    if debug:
        os.makedirs("logs", exist_ok=True)
        handlers["file"] = {
            "level": log_level,
            "formatter": "standard",
            "class": "logging.handlers.RotatingFileHandler",
            "filename": "logs/app.log",
            "maxBytes": 10 * 1024 * 1024,
            "backupCount": 5,
            "encoding": "utf-8",
        }

    logging_config = {
        "version": 1,
        "disable_existing_loggers": False,
        "formatters": {
            "standard": {
                "format": log_format,
                "datefmt": "%Y-%m-%d %H:%M:%S",
            },
            "json": {
                "()": jsonlogger.JsonFormatter,
                "format": json_format,
                "datefmt": "%Y-%m-%dT%H:%M:%S%z",
            },
        },
        "handlers": handlers,
        "loggers": {
            "": {
                "handlers": ["default", "error"] + (["file"] if debug else []),
                "level": log_level,
                "propagate": True,
            },
            "uvicorn": {
                "handlers": ["default"],
                "level": log_level if debug else "WARNING",
                "propagate": False,
            },
            "uvicorn.access": {
                "handlers": ["default"],
                "level": log_level if debug else "WARNING",
                "propagate": False,
            },
            "apscheduler": {
                "handlers": ["default"],
                "level": log_level if debug else "WARNING",
                "propagate": False,
            },
            "sqlalchemy": {
                "handlers": ["default"],
                "level": "WARNING",
                "propagate": False,
            },
        },
    }

    logging.config.dictConfig(logging_config)
