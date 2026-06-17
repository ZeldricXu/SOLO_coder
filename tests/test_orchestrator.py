from datetime import datetime, timezone, timedelta

import pytest

from etl_engine.orchestrator.dag import DAG, DAGDefinition
from etl_engine.orchestrator.scheduler import DAGScheduler


def test_dag_validation_valid(sample_dag_definition):
    definition = DAGDefinition(**sample_dag_definition)
    dag = DAG(definition)
    assert dag.validate() is True


def test_dag_validation_cycle():
    definition = DAGDefinition(
        nodes=[
            {"id": "a", "type": "extract"},
            {"id": "b", "type": "transform"},
            {"id": "c", "type": "load"},
        ],
        edges=[
            {"source": "a", "target": "b"},
            {"source": "b", "target": "c"},
            {"source": "c", "target": "a"},
        ],
    )
    dag = DAG(definition)
    assert dag.validate() is False


def test_topological_sort(sample_dag_definition):
    definition = DAGDefinition(**sample_dag_definition)
    dag = DAG(definition)
    layers = dag.get_execution_order()

    assert len(layers) == 4
    assert layers[0] == ["extract_mysql"]
    assert layers[1] == ["transform"]
    assert layers[2] == ["quality_check"]
    assert layers[3] == ["load_clickhouse"]


def test_scheduler_next_run():
    scheduler = DAGScheduler()
    base = datetime(2024, 1, 1, 0, 0, 0, tzinfo=timezone.utc)
    next_run = scheduler.get_next_run("0 * * * *", base)
    assert next_run == datetime(2024, 1, 1, 1, 0, 0, tzinfo=timezone.utc)


def test_sla_check():
    scheduler = DAGScheduler()
    sla_seconds = 60
    started_at = datetime.now(timezone.utc) - timedelta(seconds=120)
    assert scheduler.check_sla(started_at, sla_seconds) is True

    recent_start = datetime.now(timezone.utc) - timedelta(seconds=10)
    assert scheduler.check_sla(recent_start, sla_seconds) is False
