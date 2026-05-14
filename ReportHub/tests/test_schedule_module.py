import pytest
import asyncio
from typing import Dict, Any, List
from datetime import datetime, timedelta
from unittest.mock import Mock, patch, MagicMock

from reporthub.modules import ScheduleModule
from tests.data import TestDataBuilder


class TestScheduleCreation:
    def test_create_interval_schedule(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule = schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600,
            export_format="xlsx",
            notify_users=["user1@test.com", "user2@test.com"]
        )
        assert schedule is not None
        assert schedule.schedule_id.startswith("schedule_")
        assert schedule.template_id == template.template_id
        assert schedule.schedule_type == "interval"
        assert schedule.schedule_interval == 3600
        assert schedule.enabled is True
        assert len(schedule.notify_users) == 2

    def test_create_cron_schedule(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule = schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="cron",
            schedule_cron="0 8 * * *",
            export_format="pdf"
        )
        assert schedule is not None
        assert schedule.schedule_type == "cron"
        assert schedule.schedule_cron == "0 8 * * *"
        assert schedule.export_format == "pdf"

    def test_schedule_enabled_flag(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule_enabled = schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600,
            enabled=True
        )
        schedule_disabled = schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600,
            enabled=False
        )
        assert schedule_enabled.enabled is True
        assert schedule_disabled.enabled is False


class TestScheduleManagement:
    def test_get_schedule(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        created = schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600
        )
        retrieved = schedule_module.get_schedule(created.schedule_id)
        assert retrieved is not None
        assert retrieved.schedule_id == created.schedule_id

    def test_get_all_schedules(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600
        )
        schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="cron",
            schedule_cron="0 8 * * *"
        )
        schedules = schedule_module.get_all_schedules()
        assert len(schedules) >= 2

    def test_get_active_schedules(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600,
            enabled=True
        )
        schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600,
            enabled=False
        )
        active = schedule_module.get_active_schedules()
        for s in active:
            assert s.enabled is True

    def test_update_schedule(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule = schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600
        )
        updated = schedule_module.update_schedule(
            schedule.schedule_id,
            schedule_interval=7200,
            export_format="pdf"
        )
        assert updated is not None
        assert updated.schedule_interval == 7200
        assert updated.export_format == "pdf"

    def test_enable_disable_schedule(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule = schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600,
            enabled=False
        )
        schedule_module.enable_schedule(schedule.schedule_id)
        schedule = schedule_module.get_schedule(schedule.schedule_id)
        assert schedule.enabled is True
        schedule_module.disable_schedule(schedule.schedule_id)
        schedule = schedule_module.get_schedule(schedule.schedule_id)
        assert schedule.enabled is False

    def test_delete_schedule(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule = schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600
        )
        delete_success = schedule_module.delete_schedule(schedule.schedule_id)
        assert delete_success is True
        retrieved = schedule_module.get_schedule(schedule.schedule_id)
        assert retrieved is None


class TestCronExpressionParsing:
    def test_cron_match_any(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        test_time = datetime(2026, 1, 15, 10, 30)
        assert schedule_module._check_cron("* * * * *", test_time) is True

    def test_cron_match_specific_values(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        test_time = datetime(2026, 1, 15, 10, 30)
        assert schedule_module._check_cron("30 10 * * *", test_time) is True
        assert schedule_module._check_cron("30 11 * * *", test_time) is False

    def test_cron_match_range(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        test_time = datetime(2026, 1, 15, 10, 30)
        assert schedule_module._check_cron("30 9-17 * * *", test_time) is True
        assert schedule_module._check_cron("30 18-23 * * *", test_time) is False

    def test_cron_match_list(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        test_time = datetime(2026, 1, 15, 10, 30)
        assert schedule_module._check_cron("30 8,10,12 * * *", test_time) is True
        assert schedule_module._check_cron("30 7,9,11 * * *", test_time) is False

    def test_cron_invalid_format(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        test_time = datetime(2026, 1, 15, 10, 30)
        assert schedule_module._check_cron("invalid", test_time) is False
        assert schedule_module._check_cron("* *", test_time) is False


class TestScheduleDueDetection:
    def test_new_schedule_is_due(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule = schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600
        )
        due = schedule_module._is_schedule_due(schedule, datetime.utcnow())
        assert due is True

    def test_interval_schedule_due(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule = schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600
        )
        schedule.last_run_at = datetime.utcnow() - timedelta(seconds=4000)
        due = schedule_module._is_schedule_due(schedule, datetime.utcnow())
        assert due is True

    def test_interval_schedule_not_due(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule = schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600
        )
        schedule.last_run_at = datetime.utcnow() - timedelta(seconds=1800)
        due = schedule_module._is_schedule_due(schedule, datetime.utcnow())
        assert due is False

    def test_cron_schedule_due(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule = schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="cron",
            schedule_cron="0 8 * * *"
        )
        test_time = datetime(2026, 1, 15, 8, 0)
        schedule.last_run_at = test_time - timedelta(days=1)
        due = schedule_module._is_schedule_due(schedule, test_time)
        assert due is True

    def test_check_due_schedules(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600,
            enabled=True
        )
        due = schedule_module.check_due_schedules()
        assert len(due) > 0
        for item in due:
            assert "schedule_id" in item
            assert "template_id" in item
            assert "export_format" in item
            assert "notify_users" in item


class TestScheduleExecution:
    def test_schedule_last_run_update(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule = schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600
        )
        before_update = schedule.last_run_at
        next_run = datetime.utcnow() + timedelta(hours=1)
        schedule_module.update_last_run(schedule.schedule_id, next_run)
        updated = schedule_module.get_schedule(schedule.schedule_id)
        assert updated.last_run_at is not None
        assert updated.next_run_at == next_run
        if before_update:
            assert updated.last_run_at > before_update

    def test_scheduler_registration(self, schedule_module, in_memory_db):
        mock_scheduler = MagicMock()
        schedule_module.register_scheduler(mock_scheduler)
        assert schedule_module._scheduler == mock_scheduler


class TestScheduleNotifications:
    def test_notify_users_list(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        users = ["admin@company.com", "manager@company.com", "analyst@company.com"]
        schedule = schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600,
            notify_users=users
        )
        assert len(schedule.notify_users) == 3
        assert "admin@company.com" in schedule.notify_users

    def test_empty_notification_list(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule = schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=3600,
            notify_users=None
        )
        assert schedule.notify_users == []


class TestScheduleQueue:
    def test_multiple_schedules_queue(self, schedule_module, in_memory_db, test_builder):
        template1 = test_builder.create_mock_template(in_memory_db, custom_id="tpl_1")
        template2 = test_builder.create_mock_template(in_memory_db, custom_id="tpl_2")
        template3 = test_builder.create_mock_template(in_memory_db, custom_id="tpl_3")
        for i, template in enumerate([template1, template2, template3]):
            schedule_module.create_schedule(
                template_id=template.template_id,
                schedule_type="interval",
                schedule_interval=3600,
                export_format="xlsx",
                notify_users=[f"user{i+1}@test.com"]
            )
        all_schedules = schedule_module.get_all_schedules()
        assert len(all_schedules) >= 3
        due = schedule_module.check_due_schedules()
        assert len(due) >= 3


class TestScheduleNonBlocking:
    def test_schedule_does_not_block_on_long_running_task(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type="interval",
            schedule_interval=1,
            export_format="xlsx"
        )
        start = datetime.utcnow()
        due = schedule_module.check_due_schedules()
        elapsed = (datetime.utcnow() - start).total_seconds()
        assert len(due) > 0
        assert elapsed < 5.0


class TestScheduleDataBuilder:
    def test_create_schedule_with_data_builder(self, schedule_module, in_memory_db, test_builder):
        template = test_builder.create_mock_template(in_memory_db)
        schedule_data = test_builder.create_schedule_data(
            template_id=template.template_id,
            schedule_type="cron",
            schedule_cron="0 9 * * 1-5",
            export_format="pdf",
            notify_users=["report@company.com"]
        )
        schedule = schedule_module.create_schedule(
            template_id=template.template_id,
            schedule_type=schedule_data["schedule_type"],
            schedule_cron=schedule_data["schedule_cron"],
            export_format=schedule_data["export_format"],
            notify_users=schedule_data["notify_users"]
        )
        assert schedule.schedule_type == "cron"
        assert schedule.schedule_cron == "0 9 * * 1-5"
        assert schedule.export_format == "pdf"
        assert "report@company.com" in schedule.notify_users
