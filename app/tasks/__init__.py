"""
Celery任务队列模块
"""
from .worker import app as celery_app

__all__ = ["celery_app"]
