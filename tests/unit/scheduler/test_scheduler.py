import pytest
from datetime import datetime, timedelta
from unittest import mock
import uuid

from tests.app.exceptions import (
    ValidationError,
    NotFoundError,
    TaskDisabledError,
    InvalidCronExpressionError,
    DatabaseError,
)
from tests.factories.data_factory import TaskFactory

pytestmark = pytest.mark.unit

class TestSchedulerTaskCreation:
    @pytest.mark.validation
    def test_create_task_success(self, scheduler):
        task_data = TaskFactory.create_task_data()
        task = scheduler.create_task(task_data)

        assert task.id is not None
        assert task.name == task_data["name"]
        assert task.cron_expr == task_data["cron_expr"]
        assert task.command == task_data["command"]
        assert task.enabled is True
        assert task.status == "idle"
        assert isinstance(task.created_at, datetime)
        assert isinstance(task.updated_at, datetime)

    @pytest.mark.validation
    def test_create_task_with_custom_id(self, scheduler):
        custom_id = str(uuid.uuid4())
        task_data = TaskFactory.create_task_data()
        task_data["id"] = custom_id

        task = scheduler.create_task(task_data)
        assert task.id == custom_id

    @pytest.mark.validation
    @pytest.mark.parametrize("invalid_data", TaskFactory.create_invalid_task_data())
    def test_create_task_validation_errors(self, scheduler, invalid_data):
        with pytest.raises(ValidationError):
            scheduler.create_task(invalid_data)

    @pytest.mark.boundary
    def test_create_task_duplicate_id(self, scheduler):
        task_data = TaskFactory.create_task_data()
        scheduler.create_task(task_data)

        with pytest.raises(ValidationError) as exc_info:
            scheduler.create_task(task_data)

        assert "already exists" in str(exc_info.value)

    @pytest.mark.boundary
    def test_create_task_max_name_length(self, scheduler):
        task_data = TaskFactory.create_task_data(name="x" * 255)
        task = scheduler.create_task(task_data)
        assert len(task.name) == 255

    @pytest.mark.transaction
    def test_create_task_with_db_success(self, scheduler_with_db, mock_db_session):
        task_data = TaskFactory.create_task_data()
        task = scheduler_with_db.create_task(task_data)

        mock_db_session.add.assert_called_once()
        mock_db_session.commit.assert_called_once()
        mock_db_session.rollback.assert_not_called()

    @pytest.mark.transaction
    def test_create_task_db_rollback_on_error(self, mocker):
        from tests.app.scheduler import Scheduler
        failing_session = mocker.MagicMock()
        failing_session.add = mocker.MagicMock()
        failing_session.commit = mocker.MagicMock(side_effect=Exception("DB error"))
        failing_session.rollback = mocker.MagicMock()

        scheduler = Scheduler(db_session=failing_session)
        task_data = TaskFactory.create_task_data()

        with pytest.raises(DatabaseError) as exc_info:
            scheduler.create_task(task_data)

        assert "create task" in str(exc_info.value)
        failing_session.rollback.assert_called_once()
        assert task_data["id"] not in scheduler._tasks

class TestSchedulerTaskRetrieval:
    def test_get_task_success(self, scheduler, sample_task):
        retrieved = scheduler.get_task(sample_task.id)
        assert retrieved.id == sample_task.id
        assert retrieved.name == sample_task.name

    def test_get_task_not_found(self, scheduler):
        with pytest.raises(NotFoundError) as exc_info:
            scheduler.get_task("non_existent_id")
        assert "Task" in str(exc_info.value)
        assert "non_existent_id" in str(exc_info.value)

    def test_list_tasks_empty(self, scheduler):
        tasks = scheduler.list_tasks()
        assert tasks == []

    def test_list_tasks_with_data(self, scheduler):
        for _ in range(5):
            scheduler.create_task(TaskFactory.create_task_data())

        tasks = scheduler.list_tasks()
        assert len(tasks) == 5

    def test_list_tasks_with_status_filter(self, scheduler):
        for _ in range(3):
            scheduler.create_task(TaskFactory.create_task_data())

        task = scheduler.create_task(TaskFactory.create_task_data())
        task.status = "running"

        running_tasks = scheduler.list_tasks(status="running")
        assert len(running_tasks) == 1
        assert running_tasks[0].id == task.id

    @pytest.mark.boundary
    def test_list_tasks_pagination_limit(self, scheduler):
        for i in range(150):
            scheduler.create_task(TaskFactory.create_task_data(name=f"task_{i}"))

        tasks = scheduler.list_tasks(limit=100)
        assert len(tasks) == 100

    @pytest.mark.boundary
    def test_list_tasks_ordered_by_created_at_desc(self, scheduler):
        for i in range(5):
            scheduler.create_task(TaskFactory.create_task_data(name=f"task_{i}"))

        tasks = scheduler.list_tasks()
        names = [t.name for t in tasks]
        assert names == sorted(names, reverse=True)

class TestSchedulerTaskUpdate:
    def test_update_task_success(self, scheduler, sample_task):
        update_data = {"name": "updated_name", "enabled": False}
        updated = scheduler.update_task(sample_task.id, update_data)

        assert updated.name == "updated_name"
        assert updated.enabled is False
        assert updated.updated_at >= sample_task.updated_at

    def test_update_task_not_found(self, scheduler):
        with pytest.raises(NotFoundError):
            scheduler.update_task("non_existent", {"name": "test"})

    @pytest.mark.validation
    def test_update_task_invalid_cron(self, scheduler, sample_task):
        with pytest.raises(InvalidCronExpressionError):
            scheduler.update_task(sample_task.id, {"cron_expr": "invalid"})

    @pytest.mark.transaction
    def test_update_task_db_rollback_on_error(self, mocker, sample_task):
        from tests.app.scheduler import Scheduler
        failing_session = mocker.MagicMock()
        failing_session.commit = mocker.MagicMock(side_effect=Exception("DB error"))
        failing_session.rollback = mocker.MagicMock()

        scheduler = Scheduler(db_session=failing_session)
        task_data = TaskFactory.create_task_data()
        scheduler._tasks[task_data["id"]] = scheduler._create_task_instance(task_data)

        with pytest.raises(DatabaseError):
            scheduler.update_task(task_data["id"], {"name": "updated"})

        failing_session.rollback.assert_called_once()

    @pytest.mark.boundary
    def test_update_task_empty_update(self, scheduler, sample_task):
        original_name = sample_task.name
        updated = scheduler.update_task(sample_task.id, {})
        assert updated.name == original_name

class TestSchedulerTaskDeletion:
    def test_delete_task_success(self, scheduler, sample_task):
        scheduler.delete_task(sample_task.id)

        with pytest.raises(NotFoundError):
            scheduler.get_task(sample_task.id)

        assert sample_task.id not in scheduler._task_runs

    def test_delete_task_not_found(self, scheduler):
        with pytest.raises(NotFoundError):
            scheduler.delete_task("non_existent")

    @pytest.mark.transaction
    def test_delete_task_db_rollback_on_error(self, mocker, sample_task):
        from tests.app.scheduler import Scheduler
        failing_session = mocker.MagicMock()
        failing_session.delete = mocker.MagicMock(side_effect=Exception("DB error"))
        failing_session.rollback = mocker.MagicMock()

        scheduler = Scheduler(db_session=failing_session)
        task_data = TaskFactory.create_task_data()
        task = scheduler._create_task_instance(task_data)
        scheduler._tasks[task_data["id"]] = task
        scheduler._task_runs[task_data["id"]] = []

        with pytest.raises(DatabaseError):
            scheduler.delete_task(task_data["id"])

        failing_session.rollback.assert_called_once()
        assert task_data["id"] in scheduler._tasks

class TestSchedulerTaskTrigger:
    def test_trigger_task_success(self, scheduler, sample_task):
        run = scheduler.trigger_task(sample_task.id)

        assert run.entity_id == sample_task.id
        assert run.phase == "running"
        assert run.progress == 0.0
        assert sample_task.status == "running"
        assert sample_task.last_run is not None

    def test_trigger_disabled_task(self, scheduler):
        task_data = TaskFactory.create_task_data(enabled=False)
        task = scheduler.create_task(task_data)

        with pytest.raises(TaskDisabledError) as exc_info:
            scheduler.trigger_task(task.id)
        assert task.id in str(exc_info.value)

    def test_trigger_task_not_found(self, scheduler):
        with pytest.raises(NotFoundError):
            scheduler.trigger_task("non_existent")

    @pytest.mark.transaction
    def test_trigger_task_db_rollback_on_error(self, mocker):
        from tests.app.scheduler import Scheduler
        normal_session = mocker.MagicMock()
        normal_session.add = mocker.MagicMock()
        normal_session.commit = mocker.MagicMock()
        normal_session.delete = mocker.MagicMock()
        normal_session.rollback = mocker.MagicMock()

        scheduler = Scheduler(db_session=normal_session)
        task_data = TaskFactory.create_task_data()
        scheduler.create_task(task_data)

        normal_session.commit = mocker.MagicMock(side_effect=Exception("DB error"))

        with pytest.raises(DatabaseError):
            scheduler.trigger_task(task_data["id"])

        normal_session.rollback.assert_called_once()

class TestSchedulerRunManagement:
    def test_complete_run_success(self, scheduler, sample_task):
        run = scheduler.trigger_task(sample_task.id)
        completed = scheduler.complete_run(run.run_id, success=True)

        assert completed.phase == "completed"
        assert completed.progress == 1.0
        assert completed.completed_at is not None
        assert sample_task.status == "idle"

    def test_complete_run_with_error(self, scheduler, sample_task):
        run = scheduler.trigger_task(sample_task.id)
        completed = scheduler.complete_run(
            run.run_id, success=False, error_detail="Timeout error"
        )

        assert completed.phase == "failed"
        assert completed.error_detail == "Timeout error"

    def test_complete_run_not_found(self, scheduler):
        with pytest.raises(NotFoundError):
            scheduler.complete_run("non_existent")

    def test_get_run_history(self, scheduler, sample_task):
        for _ in range(5):
            run = scheduler.trigger_task(sample_task.id)
            scheduler.complete_run(run.run_id)

        history = scheduler.get_run_history(sample_task.id, limit=3)
        assert len(history) == 3

    def test_get_run_history_not_found(self, scheduler):
        with pytest.raises(NotFoundError):
            scheduler.get_run_history("non_existent", 10)

    @pytest.mark.boundary
    def test_get_run_history_empty(self, scheduler, sample_task):
        history = scheduler.get_run_history(sample_task.id)
        assert history == []

    @pytest.mark.boundary
    def test_get_run_history_ordered_by_time_desc(self, scheduler, sample_task):
        runs = []
        for _ in range(5):
            run = scheduler.trigger_task(sample_task.id)
            scheduler.complete_run(run.run_id)
            runs.append(run)

        history = scheduler.get_run_history(sample_task.id)
        history_ids = [r.run_id for r in history]
        expected_ids = [r.run_id for r in reversed(runs)]
        assert history_ids == expected_ids

class TestSchedulerCronValidation:
    @pytest.mark.parametrize("expr,expected", [
        ("*/5 * * * * *", True),
        ("0 0 2 * * *", True),
        ("0,30 * * * * *", True),
        ("1-5 * * * * *", True),
        ("invalid", False),
        ("* * * *", False),
        ("", False),
        (None, False),
        (123, False),
    ])
    def test_cron_expression_validation(self, scheduler, expr, expected):
        assert scheduler._validate_cron_expr(expr, with_seconds=True) == expected

    def test_calculate_next_run(self, scheduler):
        next_run = scheduler.calculate_next_run("*/5 * * * * *")
        assert isinstance(next_run, datetime)
        assert next_run > datetime.utcnow()

    def test_calculate_next_run_invalid_cron(self, scheduler):
        with pytest.raises(InvalidCronExpressionError):
            scheduler.calculate_next_run("invalid")
