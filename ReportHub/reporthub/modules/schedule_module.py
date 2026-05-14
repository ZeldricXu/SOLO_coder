import uuid
from typing import Optional, List, Dict, Any, Callable
from datetime import datetime
from sqlalchemy.orm import Session

from reporthub.models import Schedule
from reporthub.modules.template_module import TemplateModule
from reporthub.modules.redis_module import (
    RedisScheduleQueue,
    is_redis_available
)
from reporthub.config.settings import settings


class ScheduleModule:
    def __init__(self, db: Session, template_module: TemplateModule, use_redis: bool = None):
        self.db = db
        self.template_module = template_module
        if use_redis is None:
            use_redis = is_redis_available()
        self.use_redis = use_redis
        if use_redis:
            self.redis_queue = RedisScheduleQueue()
        else:
            self.redis_queue = None
        self._scheduler = None

    def create_schedule(self, template_id: str, schedule_type: str = "cron",
                        schedule_cron: Optional[str] = None,
                        schedule_interval: Optional[int] = None,
                        export_format: str = "xlsx",
                        notify_users: Optional[List[str]] = None,
                        enabled: bool = True) -> Schedule:
        schedule_id = f"schedule_{uuid.uuid4().hex[:12]}"
        schedule = Schedule(
            schedule_id=schedule_id,
            template_id=template_id,
            schedule_type=schedule_type,
            schedule_cron=schedule_cron,
            schedule_interval=schedule_interval,
            export_format=export_format,
            notify_users=notify_users or [],
            enabled=enabled
        )
        self.db.add(schedule)
        self.db.commit()
        self.db.refresh(schedule)
        return schedule

    def get_schedule(self, schedule_id: str) -> Optional[Schedule]:
        return self.db.query(Schedule).filter(Schedule.schedule_id == schedule_id).first()

    def get_all_schedules(self) -> List[Schedule]:
        return self.db.query(Schedule).all()

    def get_active_schedules(self) -> List[Schedule]:
        return self.db.query(Schedule).filter(Schedule.enabled == True).all()

    def update_schedule(self, schedule_id: str, **kwargs) -> Optional[Schedule]:
        schedule = self.get_schedule(schedule_id)
        if not schedule:
            return None
        for key, value in kwargs.items():
            if hasattr(schedule, key):
                setattr(schedule, key, value)
        self.db.commit()
        self.db.refresh(schedule)
        return schedule

    def delete_schedule(self, schedule_id: str) -> bool:
        schedule = self.get_schedule(schedule_id)
        if not schedule:
            return False
        self.db.delete(schedule)
        self.db.commit()
        return True

    def enable_schedule(self, schedule_id: str) -> bool:
        schedule = self.get_schedule(schedule_id)
        if not schedule:
            return False
        schedule.enabled = True
        self.db.commit()
        return True

    def disable_schedule(self, schedule_id: str) -> bool:
        schedule = self.get_schedule(schedule_id)
        if not schedule:
            return False
        schedule.enabled = False
        self.db.commit()
        return True

    def update_last_run(self, schedule_id: str, next_run_at: Optional[datetime] = None) -> bool:
        schedule = self.get_schedule(schedule_id)
        if not schedule:
            return False
        schedule.last_run_at = datetime.utcnow()
        if next_run_at:
            schedule.next_run_at = next_run_at
        self.db.commit()
        return True

    def register_scheduler(self, scheduler) -> None:
        self._scheduler = scheduler

    def _send_notification(self, users: List[str], report_name: str, report_file: str) -> None:
        pass

    def check_due_schedules(self) -> List[Dict[str, Any]]:
        active_schedules = self.get_active_schedules()
        due_schedules = []
        current_time = datetime.utcnow()
        for schedule in active_schedules:
            if self._is_schedule_due(schedule, current_time):
                due_schedules.append({
                    "schedule_id": schedule.schedule_id,
                    "template_id": schedule.template_id,
                    "export_format": schedule.export_format,
                    "notify_users": schedule.notify_users
                })
        return due_schedules

    def trigger_schedule(self, schedule_id: str, priority: bool = False) -> Optional[str]:
        schedule = self.get_schedule(schedule_id)
        if not schedule:
            return None
        if self.use_redis and self.redis_queue:
            task_id = self.redis_queue.submit_task(
                schedule_id=schedule_id,
                template_id=schedule.template_id,
                export_format=schedule.export_format,
                notify_users=schedule.notify_users,
                priority=priority
            )
            return task_id
        return None

    def check_and_trigger_due_schedules(self) -> List[str]:
        due_schedules = self.check_due_schedules()
        triggered_task_ids = []
        for due in due_schedules:
            if self.use_redis and self.redis_queue:
                task_id = self.redis_queue.submit_task(
                    schedule_id=due["schedule_id"],
                    template_id=due["template_id"],
                    export_format=due["export_format"],
                    notify_users=due["notify_users"]
                )
                triggered_task_ids.append(task_id)
                self.update_last_run(due["schedule_id"])
        return triggered_task_ids

    def get_scheduled_task_status(self, task_id: str) -> Optional[Dict[str, Any]]:
        if self.use_redis and self.redis_queue:
            return self.redis_queue.get_task_status(task_id)
        return None

    def get_scheduled_queue_size(self) -> int:
        if self.use_redis and self.redis_queue:
            return self.redis_queue.get_queue_size()
        return 0

    def clear_scheduled_queue(self) -> bool:
        if self.use_redis and self.redis_queue:
            return self.redis_queue.clear_queue()
        return False

    def _is_schedule_due(self, schedule: Schedule, current_time: datetime) -> bool:
        if schedule.last_run_at is None:
            return True
        if schedule.schedule_type == "interval" and schedule.schedule_interval:
            import datetime as dt
            return (current_time - schedule.last_run_at).total_seconds() >= schedule.schedule_interval
        if schedule.schedule_type == "cron" and schedule.schedule_cron:
            return self._check_cron(schedule.schedule_cron, current_time)
        return False

    def _check_cron(self, cron_expr: str, current_time: datetime) -> bool:
        parts = cron_expr.split()
        if len(parts) != 5:
            return False
        minute, hour, day, month, weekday = parts
        return (
            self._match_cron_part(minute, current_time.minute) and
            self._match_cron_part(hour, current_time.hour) and
            self._match_cron_part(day, current_time.day) and
            self._match_cron_part(month, current_time.month) and
            self._match_cron_part(weekday, current_time.weekday())
        )

    def _match_cron_part(self, cron_part: str, value: int) -> bool:
        if cron_part == "*":
            return True
        if "," in cron_part:
            return any(self._match_cron_part(p, value) for p in cron_part.split(","))
        if "-" in cron_part:
            start, end = map(int, cron_part.split("-"))
            return start <= value <= end
        try:
            return int(cron_part) == value
        except ValueError:
            return False


class ScheduleExecutionWorker:
    def __init__(self, db: Session, template_module: TemplateModule):
        self.db = db
        self.template_module = template_module
        self.redis_queue = RedisScheduleQueue()
        self.running = False

    def start(self, max_tasks: Optional[int] = None):
        self.running = True
        processed = 0
        while self.running:
            if max_tasks and processed >= max_tasks:
                break
            task = self.redis_queue.get_next_task(timeout=1)
            if not task:
                continue
            try:
                result = self._process_task(task)
                self.redis_queue.complete_task(task["task_id"], result.get("report_id", ""))
                processed += 1
            except Exception as e:
                self.redis_queue.fail_task(task["task_id"], str(e))
                processed += 1

    def stop(self):
        self.running = False

    def _process_task(self, task: Dict[str, Any]) -> Dict[str, Any]:
        from reporthub.modules.data_module import DataModule
        from reporthub.modules.storage_module import StorageModule
        from reporthub.modules.version_module import VersionModule
        from reporthub.modules.statistics_module import StatisticsModule
        template_id = task["template_id"]
        schedule_id = task["schedule_id"]
        notify_users = task.get("notify_users", [])
        template = self.template_module.get_template(template_id)
        if not template:
            raise Exception(f"Template not found: {template_id}")
        storage_module = StorageModule()
        version_module = VersionModule(self.db, storage_module)
        statistics_module = StatisticsModule(self.db)
        data_module = DataModule(
            self.db,
            storage_module,
            version_module,
            statistics_module
        )
        report = data_module.generate_report(
            template,
            report_params={"schedule_id": schedule_id},
            generator="schedule_system"
        )
        if notify_users:
            self._notify_users(notify_users, report.report_name, report.report_id)
        return {
            "report_id": report.report_id,
            "schedule_id": schedule_id,
            "template_id": template_id,
            "generated_at": datetime.utcnow().isoformat()
        }

    def _notify_users(self, users: List[str], report_name: str, report_id: str) -> None:
        for user in users:
            pass
