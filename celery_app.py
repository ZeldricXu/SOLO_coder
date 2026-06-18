import logging
from celery import Celery
from celery.signals import setup_logging

from config.settings import settings

logger = logging.getLogger(__name__)


def make_celery(app_name: str = "genome_pipeline") -> Celery:
    celery = Celery(
        app_name,
        broker=settings.redis.url,
        backend=settings.redis.url,
        include=["tasks"],
    )

    celery.conf.update(
        task_serializer="json",
        accept_content=["json"],
        result_serializer="json",
        timezone="Asia/Shanghai",
        enable_utc=True,
        task_track_started=True,
        task_time_limit=86400 * 7,
        task_soft_time_limit=86400 * 6,
        worker_prefetch_multiplier=1,
        worker_max_tasks_per_child=100,
        result_expires=86400 * 30,
        task_default_retry_delay=300,
        task_max_retries=3,
        broker_connection_retry_on_startup=True,
        broker_connection_max_retries=10,
    )

    return celery


@setup_logging.connect
def config_loggers(*args, **kwargs):
    from logging.config import dictConfig

    dictConfig(
        {
            "version": 1,
            "disable_existing_loggers": False,
            "formatters": {
                "verbose": {
                    "format": "%(levelname)s %(asctime)s %(module)s %(process)d %(thread)d %(message)s"
                },
                "simple": {"format": "%(levelname)s %(message)s"},
            },
            "handlers": {
                "console": {
                    "level": "INFO",
                    "class": "logging.StreamHandler",
                    "formatter": "verbose",
                },
                "file": {
                    "level": "INFO",
                    "class": "logging.handlers.RotatingFileHandler",
                    "filename": f"{settings.pipeline.log_dir}/celery.log",
                    "maxBytes": 10 * 1024 * 1024,
                    "backupCount": 10,
                    "formatter": "verbose",
                },
            },
            "loggers": {
                "": {"handlers": ["console", "file"], "level": "INFO", "propagate": True},
                "celery": {"handlers": ["console", "file"], "level": "INFO", "propagate": False},
            },
        }
    )


celery = make_celery()

if __name__ == "__main__":
    celery.start()
