from __future__ import annotations

import uuid
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional
from faker import Faker

fake = Faker()

class TaskFactory:
    @staticmethod
    def create_task_data(
        name: Optional[str] = None,
        cron_expr: Optional[str] = None,
        command: Optional[str] = None,
        enabled: bool = True,
        parameters: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        return {
            "id": str(uuid.uuid4()),
            "name": name or f"task_{fake.word()}_{fake.random_int(1, 9999)}",
            "cron_expr": cron_expr or "*/5 * * * * *",
            "command": command or f"scripts/{fake.word()}.sh",
            "parameters": parameters or {"timeout": 30, "retries": 3},
            "enabled": enabled,
        }

    @staticmethod
    def create_invalid_task_data() -> List[Dict[str, Any]]:
        return [
            {"name": "", "cron_expr": "*/5 * * * * *", "command": "test.sh"},
            {"name": None, "cron_expr": "*/5 * * * * *", "command": "test.sh"},
            {"name": "x" * 256, "cron_expr": "*/5 * * * * *", "command": "test.sh"},
            {"name": "test", "cron_expr": "invalid", "command": "test.sh"},
            {"name": "test", "cron_expr": None, "command": "test.sh"},
            {"name": "test", "cron_expr": "*/5 * * * * *", "command": ""},
            {"name": "test", "cron_expr": "*/5 * * * * *", "command": None},
            {"name": "test", "cron_expr": "*/5 * * * * *", "command": "test.sh", "parameters": "not a dict"},
            {"name": "test", "cron_expr": "*/5 * * * * *", "command": "test.sh", "enabled": "not bool"},
        ]

class SLOFactory:
    @staticmethod
    def create_slo_data(
        name: Optional[str] = None,
        service_name: Optional[str] = None,
        sli: Optional[str] = None,
        target_percent: float = 99.9,
        error_budget: float = 0.001,
        window_days: int = 30,
    ) -> Dict[str, Any]:
        return {
            "id": str(uuid.uuid4()),
            "name": name or f"slo_{fake.word()}_{fake.random_int(1, 9999)}",
            "service_name": service_name or f"service_{fake.word()}",
            "sli": sli or "availability",
            "target_percent": target_percent,
            "error_budget": error_budget,
            "window_days": window_days,
        }

    @staticmethod
    def create_invalid_slo_data() -> List[Dict[str, Any]]:
        return [
            {"name": "", "service_name": "svc", "sli": "latency", "target_percent": 99.9, "error_budget": 0.001},
            {"name": "x" * 256, "service_name": "svc", "sli": "latency", "target_percent": 99.9, "error_budget": 0.001},
            {"name": "test", "service_name": "", "sli": "latency", "target_percent": 99.9, "error_budget": 0.001},
            {"name": "test", "service_name": "svc", "sli": "", "target_percent": 99.9, "error_budget": 0.001},
            {"name": "test", "service_name": "svc", "sli": "latency", "target_percent": -1, "error_budget": 0.001},
            {"name": "test", "service_name": "svc", "sli": "latency", "target_percent": 101, "error_budget": 0.001},
            {"name": "test", "service_name": "svc", "sli": "latency", "target_percent": 99.9, "error_budget": -0.01},
            {"name": "test", "service_name": "svc", "sli": "latency", "target_percent": 99.9, "error_budget": 1.5},
            {"name": "test", "service_name": "svc", "sli": "latency", "target_percent": 99.9, "error_budget": 0.001, "window_days": 0},
            {"name": "test", "service_name": "svc", "sli": "latency", "target_percent": 99.9, "error_budget": 0.001, "window_days": 400},
        ]

    @staticmethod
    def create_metric_event(
        service_name: str,
        sli: str,
        success: bool = True,
        timestamp: Optional[datetime] = None,
    ) -> Dict[str, Any]:
        return {
            "service_name": service_name,
            "sli": sli,
            "success": success,
            "timestamp": timestamp or datetime.utcnow(),
        }

class StorageFactory:
    @staticmethod
    def create_file_data(
        name: Optional[str] = None,
        content: Optional[bytes] = None,
        content_type: Optional[str] = None,
        size: int = 1024,
    ) -> Dict[str, Any]:
        return {
            "name": name or f"file_{fake.word()}_{fake.random_int(1, 9999)}.txt",
            "content": content or fake.binary(length=size),
            "content_type": content_type or "text/plain",
        }

    @staticmethod
    def create_invalid_file_data() -> List[Dict[str, Any]]:
        return [
            {"name": "", "content": b"test", "content_type": "text/plain"},
            {"name": "path/with/slashes.txt", "content": b"test", "content_type": "text/plain"},
            {"name": "path\\with\\backslashes.txt", "content": b"test", "content_type": "text/plain"},
            {"name": "x" * 256 + ".txt", "content": b"test", "content_type": "text/plain"},
            {"name": "test.txt", "content": b"", "content_type": "text/plain"},
            {"name": "test.txt", "content": None, "content_type": "text/plain"},
            {"name": "test.txt", "content": "not bytes", "content_type": "text/plain"},
            {"name": "test.txt", "content": b"test", "content_type": ""},
        ]

    @staticmethod
    def create_large_file_data(size_mb: int = 10) -> Dict[str, Any]:
        return {
            "name": f"large_file_{fake.random_int(1, 9999)}.dat",
            "content": fake.binary(length=size_mb * 1024 * 1024),
            "content_type": "application/octet-stream",
        }

class DatabaseFactory:
    @staticmethod
    def create_mock_db_session(mocker):
        session = mocker.MagicMock()
        session.add = mocker.MagicMock()
        session.commit = mocker.MagicMock()
        session.delete = mocker.MagicMock()
        session.rollback = mocker.MagicMock()
        return session

    @staticmethod
    def create_failing_db_session(mocker, fail_on: str = "commit"):
        session = mocker.MagicMock()
        session.add = mocker.MagicMock()
        session.delete = mocker.MagicMock()
        session.rollback = mocker.MagicMock()

        if fail_on == "commit":
            session.commit = mocker.MagicMock(side_effect=Exception("DB connection error"))
        elif fail_on == "add":
            session.add = mocker.MagicMock(side_effect=Exception("DB add error"))
        elif fail_on == "delete":
            session.delete = mocker.MagicMock(side_effect=Exception("DB delete error"))

        return session
