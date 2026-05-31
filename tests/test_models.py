from datetime import datetime, timedelta

import pytest

from src.models import (
    APIResponse,
    BaseEntity,
    ConfigDefinition,
    EntityStatus,
    EntityType,
    MetricsSnapshot,
    Notification,
    NotificationChannel,
    NotificationStatus,
    QualityGateReport,
    QualityGateRule,
    QualityGateStatus,
    RunInstance,
    RunPhase,
    ServiceMetadata,
    Task,
    TaskGraph,
)


def test_base_entity_creation():
    entity = BaseEntity(
        type=EntityType.JOB,
        status=EntityStatus.PENDING,
        attributes={"key": "value"},
    )
    assert entity.id.startswith("ent_")
    assert entity.type == EntityType.JOB
    assert entity.status == EntityStatus.PENDING
    assert entity.attributes == {"key": "value"}
    assert isinstance(entity.created_at, datetime)
    assert isinstance(entity.updated_at, datetime)


def test_base_entity_auto_updated_at():
    entity = BaseEntity(type=EntityType.RESOURCE)
    old_updated = entity.updated_at
    entity.status = EntityStatus.RUNNING
    assert entity.updated_at >= old_updated


def test_config_definition():
    config = ConfigDefinition(
        namespace="production",
        version=2,
        parameters={"timeout": 60, "retries": 5},
        enabled=True,
    )
    assert config.config_id.startswith("cfg_")
    assert config.namespace == "production"
    assert config.version == 2
    assert config.parameters["timeout"] == 60


def test_run_instance():
    run = RunInstance(
        entity_id="ent_123",
        phase=RunPhase.EXECUTING,
        progress=0.5,
    )
    assert run.run_id.startswith("run_")
    assert run.entity_id == "ent_123"
    assert run.phase == RunPhase.EXECUTING
    assert run.progress == 0.5


def test_run_instance_progress_validation():
    with pytest.raises(ValueError):
        RunInstance(entity_id="ent_123", progress=1.5)
    with pytest.raises(ValueError):
        RunInstance(entity_id="ent_123", progress=-0.1)


def test_metrics_snapshot():
    snapshot = MetricsSnapshot(
        metrics={"throughput": 1500, "latency_p99": 250},
        dimensions={"host": "node-1", "region": "cn-east"},
    )
    assert snapshot.snapshot_id.startswith("snap_")
    assert snapshot.metrics["throughput"] == 1500
    assert snapshot.dimensions["region"] == "cn-east"


def test_task_creation():
    task = Task(
        name="data_processing",
        description="Process raw data",
        dependencies=["task_1", "task_2"],
        parameters={"input_path": "/data/raw"},
        timeout=7200,
        retries=3,
    )
    assert task.task_id.startswith("task_")
    assert task.name == "data_processing"
    assert len(task.dependencies) == 2
    assert task.timeout == 7200


def test_task_graph():
    task_a = Task(task_id="a", name="a", dependencies=[])
    task_b = Task(task_id="b", name="b", dependencies=["a"])
    task_c = Task(task_id="c", name="c", dependencies=["b"])

    graph = TaskGraph(
        name="pipeline",
        tasks=[task_c, task_a, task_b],
        parameters={"env": "prod"},
    )

    ordered = graph.get_tasks_in_order()
    assert [t.task_id for t in ordered] == ["a", "b", "c"]


def test_notification_model():
    notif = Notification(
        channel=NotificationChannel.EMAIL,
        recipient="user@example.com",
        subject="Test Alert",
        content="This is a test notification",
    )
    assert notif.notification_id.startswith("notif_")
    assert notif.channel == NotificationChannel.EMAIL
    assert notif.status == NotificationStatus.PENDING
    assert notif.retry_count == 0


def test_quality_gate_rule():
    rule = QualityGateRule(
        name="max_complexity",
        language="python",
        severity="error",
        parameters={"max_cyclomatic_complexity": 10},
    )
    assert rule.rule_id.startswith("rule_")
    assert rule.language == "python"
    assert rule.enabled is True


def test_quality_gate_report():
    report = QualityGateReport(
        project_name="my-project",
        status=QualityGateStatus.PASSED,
        language="python",
        complexity_score=5.2,
        coverage=85.5,
        duplication_rate=2.1,
        issues=[{"severity": "low", "message": "Minor issue"}],
    )
    assert report.report_id.startswith("report_")
    assert report.status == QualityGateStatus.PASSED
    assert report.coverage == 85.5


def test_service_metadata():
    svc = ServiceMetadata(
        name="order-service",
        version="2.1.0",
        description="Handles order processing",
        type="service",
        language="python",
        dependencies=["payment-service", "inventory-service"],
        tags=["orders", "production"],
        endpoints=[{"path": "/orders", "method": "POST"}],
    )
    assert svc.service_id.startswith("svc_")
    assert svc.name == "order-service"
    assert len(svc.dependencies) == 2
    assert "production" in svc.tags


def test_api_response():
    resp = APIResponse(
        code=200,
        data={"id": "123", "status": "ok"},
        message="Success",
    )
    assert resp.code == 200
    assert resp.data["id"] == "123"
    assert resp.message == "Success"


def test_entity_type_enum():
    assert EntityType.RESOURCE == "resource"
    assert EntityType.JOB == "job"
    assert EntityType.TASK == "task"
    assert EntityType.SERVICE == "service"
    assert EntityType.LIBRARY == "library"


def test_entity_status_enum():
    assert EntityStatus.PENDING == "pending"
    assert EntityStatus.RUNNING == "running"
    assert EntityStatus.COMPLETED == "completed"
    assert EntityStatus.FAILED == "failed"
