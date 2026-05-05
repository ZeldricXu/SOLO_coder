import asyncio
import logging
from typing import Optional
from dataclasses import dataclass
from datetime import datetime

from app.core.database import async_session_maker
from app.repositories.request_log_repository import RequestLogRepository

logger = logging.getLogger(__name__)


@dataclass
class LogRecord:
    model_name: Optional[str]
    duration_ms: float
    status_code: int
    endpoint: Optional[str]
    method: Optional[str]
    request_time: Optional[datetime] = None


class BackgroundTaskManager:
    def __init__(self):
        self._tasks: set = set()

    def _task_done_callback(self, task: asyncio.Task):
        try:
            self._tasks.discard(task)
            task.result()
        except asyncio.CancelledError:
            pass
        except Exception as e:
            logger.error(f"Background task failed: {e}")

    def submit(self, coro):
        task = asyncio.create_task(coro)
        self._tasks.add(task)
        task.add_done_callback(self._task_done_callback)
        return task


background_task_manager = BackgroundTaskManager()


async def save_request_log_async(
    model_name: Optional[str],
    duration_ms: float,
    status_code: int,
    endpoint: Optional[str] = None,
    method: Optional[str] = None,
    request_time: Optional[datetime] = None
):
    try:
        async with async_session_maker() as session:
            repository = RequestLogRepository(session)
            await repository.create_log(
                model_name=model_name,
                duration_ms=duration_ms,
                status_code=status_code,
                endpoint=endpoint,
                method=method,
                request_time=request_time
            )
        logger.debug(f"Request log saved asynchronously: model={model_name}, status={status_code}")
    except Exception as e:
        logger.error(f"Failed to save request log asynchronously: {e}")


def submit_log_task(
    model_name: Optional[str],
    duration_ms: float,
    status_code: int,
    endpoint: Optional[str] = None,
    method: Optional[str] = None,
    request_time: Optional[datetime] = None
):
    background_task_manager.submit(
        save_request_log_async(
            model_name=model_name,
            duration_ms=duration_ms,
            status_code=status_code,
            endpoint=endpoint,
            method=method,
            request_time=request_time
        )
    )
