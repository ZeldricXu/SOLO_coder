import pytest
from datetime import datetime, timezone
from streamsql.core.models import (
    BaseEntity,
    ConfigModel,
    RunInstance,
    StatsSnapshot,
    ColumnInfo,
    TableSchema,
    ColumnType,
)


def test_base_entity():
    entity = BaseEntity(
        id="ent_001",
        status="completed",
        attributes={"key": "value"},
    )
    assert entity.id == "ent_001"
    assert entity.status == "completed"
    assert entity.attributes["key"] == "value"
    assert entity.created_at is not None
    assert entity.updated_at is not None


def test_config_model():
    config = ConfigModel(
        config_id="cfg_001",
        namespace="production",
        version=3,
        parameters={"timeout": 30, "retries": 3},
        enabled=True,
    )
    assert config.config_id == "cfg_001"
    assert config.namespace == "production"
    assert config.version == 3
    assert config.parameters["timeout"] == 30
    assert config.enabled is True


def test_run_instance():
    run = RunInstance(
        run_id="run_001",
        entity_id="ent_001",
        phase="executing",
        progress=0.75,
    )
    assert run.run_id == "run_001"
    assert run.entity_id == "ent_001"
    assert run.phase == "executing"
    assert run.progress == 0.75
    assert run.completed_at is None
    assert run.error_detail is None


def test_run_instance_progress_validation():
    run = RunInstance(
        run_id="run_001",
        entity_id="ent_001",
        progress=1.5,
    )
    assert run.progress == 1.0

    run2 = RunInstance(
        run_id="run_002",
        entity_id="ent_001",
        progress=-0.5,
    )
    assert run2.progress == 0.0


def test_stats_snapshot():
    snapshot = StatsSnapshot(
        snapshot_id="snap_001",
        metrics={"throughput": 1500, "latency_p99": 250},
        dimensions={"host": "node-1"},
    )
    assert snapshot.snapshot_id == "snap_001"
    assert snapshot.metrics["throughput"] == 1500
    assert snapshot.dimensions["host"] == "node-1"


def test_column_info():
    col = ColumnInfo(
        name="id",
        type=ColumnType.INTEGER,
        nullable=False,
        primary_key=True,
    )
    assert col.name == "id"
    assert col.type == ColumnType.INTEGER
    assert col.nullable is False
    assert col.primary_key is True


def test_table_schema():
    schema = TableSchema(
        database="test_db",
        table="users",
        columns=[
            ColumnInfo(name="id", type=ColumnType.INTEGER, nullable=False, primary_key=True),
            ColumnInfo(name="name", type=ColumnType.STRING, nullable=False),
        ],
    )
    assert schema.database == "test_db"
    assert schema.table == "users"
    assert len(schema.columns) == 2
    assert schema.columns[0].name == "id"
