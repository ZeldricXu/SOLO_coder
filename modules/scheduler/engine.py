import asyncio
from datetime import datetime, timedelta
from typing import Any, Callable, Dict, List, Optional
from croniter import croniter

from core import emit_event, EventTypes
from models import generate_uuid, utc_now


class TaskStatus:
    PENDING = "pending"
    RUNNING = "running"
    SUCCESS = "success"
    FAILED = "failed"
    TIMEOUT = "timeout"
    PAUSED = "paused"


class SchedulerEngine:
    def __init__(self):
        self._tasks: Dict[str, Dict[str, Any]] = {}
        self._task_handlers: Dict[str, Callable] = {}
        self._running_tasks: Dict[str, asyncio.Task] = {}
        self._stop_event = asyncio.Event()
        self._scheduler_task: Optional[asyncio.Task] = None

    def register_handler(self, task_type: str, handler: Callable) -> None:
        self._task_handlers[task_type] = handler

    def add_task(self, task_config: Dict[str, Any]) -> None:
        task_id = task_config.get("id") or task_config.get("task_id")
        if task_id:
            self._tasks[task_id] = {
                **task_config,
                "status": TaskStatus.PENDING,
                "created_at": utc_now(),
            }

    def remove_task(self, task_id: str) -> None:
        if task_id in self._tasks:
            del self._tasks[task_id]
        if task_id in self._running_tasks:
            self._running_tasks[task_id].cancel()
            del self._running_tasks[task_id]

    def get_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        return self._tasks.get(task_id)

    def list_tasks(self) -> List[Dict[str, Any]]:
        return list(self._tasks.values())

    def calculate_next_run(self, task_config: Dict[str, Any]) -> Optional[datetime]:
        if not task_config.get("enabled", True):
            return None

        now = datetime.now()

        if task_config.get("run_once", False):
            if task_config.get("last_run_at"):
                return None
            return now

        cron_expr = task_config.get("cron_expression")
        if cron_expr:
            try:
                cron = croniter(cron_expr, now)
                return cron.get_next(datetime)
            except Exception:
                return None

        interval = task_config.get("interval_seconds")
        if interval:
            last_run = task_config.get("last_run_at") or now
            return last_run + timedelta(seconds=interval)

        return None

    async def _execute_task(self, task_id: str) -> None:
        task_config = self._tasks.get(task_id)
        if not task_config:
            return

        if not task_config.get("enabled", True):
            return

        task_type = task_config.get("task_type")
        handler = self._task_handlers.get(task_type)

        if handler is None:
            handler = self._default_handler

        task_config["status"] = TaskStatus.RUNNING
        task_config["last_run_at"] = utc_now()

        emit_event(
            EventTypes.TASK_CREATED,
            "scheduler",
            {"task_id": task_id, "task_type": task_type},
        )

        execution_id = generate_uuid()
        start_time = datetime.now()

        try:
            parameters = task_config.get("parameters", {})

            if asyncio.iscoroutinefunction(handler):
                result = await asyncio.wait_for(
                    handler(task_id, parameters),
                    timeout=task_config.get("timeout_seconds", 300),
                )
            else:
                result = await asyncio.wait_for(
                    asyncio.to_thread(handler, task_id, parameters),
                    timeout=task_config.get("timeout_seconds", 300),
                )

            task_config["status"] = TaskStatus.SUCCESS
            task_config["success_count"] = task_config.get("success_count", 0) + 1
            duration = (datetime.now() - start_time).total_seconds() * 1000

            emit_event(
                EventTypes.TASK_COMPLETED,
                "scheduler",
                {
                    "task_id": task_id,
                    "execution_id": execution_id,
                    "duration_ms": duration,
                    "result": result,
                },
            )

        except asyncio.TimeoutError:
            task_config["status"] = TaskStatus.TIMEOUT
            task_config["failure_count"] = task_config.get("failure_count", 0) + 1
            duration = (datetime.now() - start_time).total_seconds() * 1000

            emit_event(
                EventTypes.TASK_FAILED,
                "scheduler",
                {
                    "task_id": task_id,
                    "execution_id": execution_id,
                    "error": "Task timed out",
                    "duration_ms": duration,
                },
            )

        except Exception as e:
            task_config["status"] = TaskStatus.FAILED
            task_config["failure_count"] = task_config.get("failure_count", 0) + 1
            duration = (datetime.now() - start_time).total_seconds() * 1000

            emit_event(
                EventTypes.TASK_FAILED,
                "scheduler",
                {
                    "task_id": task_id,
                    "execution_id": execution_id,
                    "error": str(e),
                    "duration_ms": duration,
                },
            )

        finally:
            task_config["next_run_at"] = self.calculate_next_run(task_config)
            if task_id in self._running_tasks:
                del self._running_tasks[task_id]

    async def _default_handler(self, task_id: str, parameters: Dict[str, Any]) -> Dict[str, Any]:
        return {"task_id": task_id, "parameters": parameters, "handled": True}

    async def trigger_task(self, task_id: str) -> bool:
        if task_id in self._running_tasks:
            return False

        task_config = self._tasks.get(task_id)
        if not task_config or not task_config.get("enabled", True):
            return False

        task = asyncio.create_task(self._execute_task(task_id))
        self._running_tasks[task_id] = task
        return True

    async def _scheduler_loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                now = datetime.now()

                for task_id, task_config in list(self._tasks.items()):
                    if task_id in self._running_tasks:
                        continue

                    if not task_config.get("enabled", True):
                        continue

                    next_run = task_config.get("next_run_at")
                    if next_run and next_run <= now:
                        asyncio.create_task(self._execute_task(task_id))

            except Exception as e:
                print(f"Scheduler loop error: {e}")

            await asyncio.sleep(1)

    async def start(self) -> None:
        if self._scheduler_task is None or self._scheduler_task.done():
            self._stop_event.clear()
            self._scheduler_task = asyncio.create_task(self._scheduler_loop())

    async def stop(self) -> None:
        self._stop_event.set()
        if self._scheduler_task:
            self._scheduler_task.cancel()
            try:
                await self._scheduler_task
            except asyncio.CancelledError:
                pass
            self._scheduler_task = None

        for task_id, task in list(self._running_tasks.items()):
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass
        self._running_tasks.clear()


scheduler_engine = SchedulerEngine()
