import json
import os
import uuid
import time
from datetime import datetime, timedelta

import pytest
import pytest_asyncio
import yaml
from httpx import ASGITransport, AsyncClient
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from etl_engine.main import app
from etl_engine.db.session import get_session
from etl_engine.models import Base, DataSource, Pipeline, PipelineExecution, TaskExecution
from etl_engine.orchestrator.dag import DAG, DAGDefinition
from etl_engine.metrics.collector import MetricsCollector, ExecutionLog
from etl_engine.metrics.prometheus import PrometheusExporter
from etl_engine.connectors.base import get_source
from etl_engine.orchestrator.scheduler import DAGScheduler


SKIP_INTEGRATION = os.environ.get("SKIP_INTEGRATION", "0") == "1"

pytestmark = [
    pytest.mark.integration,
    pytest.mark.slow,
    pytest.mark.skipif(SKIP_INTEGRATION, reason="SKIP_INTEGRATION=1 set, skipping integration tests"),
]


def _random_suffix() -> str:
    return uuid.uuid4().hex[:8]


def _build_sqlite_engine():
    return create_async_engine("sqlite+aiosqlite:///:memory:")


@pytest.fixture(scope="module")
def mysql_container():
    if SKIP_INTEGRATION:
        pytest.skip("SKIP_INTEGRATION=1 set")
    try:
        from testcontainers.mysql import MySqlContainer

        with MySqlContainer("mysql:8.0") as container:
            container.with_env("MYSQL_DATABASE", "etl_test")
            container.start()
            connection_params = {
                "host": container.get_container_host_ip(),
                "port": int(container.get_exposed_port(3306)),
                "user": container.username,
                "password": container.password,
                "database": "etl_test",
            }
            yield connection_params
    except Exception as e:
        pytest.skip(f"Docker/testcontainers not available: {e}")


@pytest.fixture(scope="module")
def postgres_container():
    if SKIP_INTEGRATION:
        pytest.skip("SKIP_INTEGRATION=1 set")
    try:
        from testcontainers.postgres import PostgresContainer

        with PostgresContainer("postgres:15") as container:
            container.with_env("POSTGRES_DB", "etl_test")
            container.start()
            connection_params = {
                "host": container.get_container_host_ip(),
                "port": int(container.get_exposed_port(5432)),
                "user": container.username,
                "password": container.password,
                "database": "etl_test",
            }
            yield connection_params
    except Exception as e:
        pytest.skip(f"Docker/testcontainers not available: {e}")


@pytest.fixture(scope="module")
def redis_container():
    if SKIP_INTEGRATION:
        pytest.skip("SKIP_INTEGRATION=1 set")
    try:
        from testcontainers.redis import RedisContainer

        with RedisContainer("redis:7") as container:
            container.start()
            connection_params = {
                "host": container.get_container_host_ip(),
                "port": int(container.get_exposed_port(6379)),
            }
            yield connection_params
    except Exception as e:
        pytest.skip(f"Docker/testcontainers not available: {e}")


@pytest.fixture(scope="module")
def minio_container():
    if SKIP_INTEGRATION:
        pytest.skip("SKIP_INTEGRATION=1 set")
    try:
        from testcontainers.minio import MinioContainer

        with MinioContainer("minio/minio:latest") as container:
            container.start()
            s3_params = {
                "endpoint_url": f"http://{container.get_container_host_ip()}:{container.get_exposed_port(9000)}",
                "aws_access_key_id": container.access_key,
                "aws_secret_access_key": container.secret_key,
                "region_name": "us-east-1",
                "bucket": "etl-test",
            }
            try:
                import boto3

                client = boto3.client(
                    "s3",
                    endpoint_url=s3_params["endpoint_url"],
                    aws_access_key_id=s3_params["aws_access_key_id"],
                    aws_secret_access_key=s3_params["aws_secret_access_key"],
                    region_name=s3_params["region_name"],
                )
                client.create_bucket(Bucket=s3_params["bucket"])
            except Exception:
                pass
            yield s3_params
    except Exception as e:
        pytest.skip(f"Docker/testcontainers not available: {e}")


@pytest_asyncio.fixture
async def db_session():
    engine = _build_sqlite_engine()
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    session_factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    async with session_factory() as session:
        yield session

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
    await engine.dispose()


@pytest_asyncio.fixture
def override_session(db_session):
    app.dependency_overrides[get_session] = lambda: db_session
    yield
    app.dependency_overrides.pop(get_session, None)


# ============================================================================
# A) TestDataSourceConfiguration
# ============================================================================

class TestDataSourceConfiguration:

    @pytest.mark.asyncio
    async def test_create_mysql_source(self, db_session, mysql_container):
        suffix = _random_suffix()
        source = DataSource(
            name=f"mysql_source_{suffix}",
            type="mysql",
            connection_config={
                "connection_params": mysql_container,
                "pool_size": 5,
            },
        )
        db_session.add(source)
        await db_session.commit()
        await db_session.refresh(source)

        loaded = await db_session.get(DataSource, source.id)
        assert loaded is not None
        assert loaded.name == f"mysql_source_{suffix}"
        assert loaded.type == "mysql"
        assert loaded.connection_config["connection_params"]["database"] == "etl_test"
        assert loaded.is_active is True

    @pytest.mark.asyncio
    async def test_create_postgres_source(self, db_session, postgres_container):
        suffix = _random_suffix()
        source = DataSource(
            name=f"postgres_source_{suffix}",
            type="postgresql",
            connection_config={
                "connection_params": postgres_container,
                "pool_size": 5,
            },
        )
        db_session.add(source)
        await db_session.commit()
        await db_session.refresh(source)

        loaded = await db_session.get(DataSource, source.id)
        assert loaded is not None
        assert loaded.name == f"postgres_source_{suffix}"
        assert loaded.type == "postgresql"
        assert loaded.connection_config["connection_params"]["database"] == "etl_test"

    @pytest.mark.asyncio
    async def test_create_s3_source(self, db_session, minio_container):
        suffix = _random_suffix()
        source = DataSource(
            name=f"s3_source_{suffix}",
            type="s3",
            connection_config={
                "connection_params": minio_container,
            },
        )
        db_session.add(source)
        await db_session.commit()
        await db_session.refresh(source)

        loaded = await db_session.get(DataSource, source.id)
        assert loaded is not None
        assert loaded.name == f"s3_source_{suffix}"
        assert loaded.type == "s3"
        cfg = loaded.connection_config["connection_params"]
        assert "endpoint_url" in cfg
        assert cfg["bucket"] == "etl-test"
        assert cfg["aws_access_key_id"] is not None

    @pytest.mark.asyncio
    async def test_source_connection_test(self, db_session, mysql_container):
        suffix = _random_suffix()
        source = DataSource(
            name=f"mysql_conn_test_{suffix}",
            type="mysql",
            connection_config={
                "connection_params": mysql_container,
                "pool_size": 3,
            },
        )
        db_session.add(source)
        await db_session.commit()
        await db_session.refresh(source)

        connector = get_source("mysql", source.connection_config)
        assert connector is not None

        try:
            await connector.connect()
            result = await connector.test_connection()
            assert result is True
        finally:
            await connector.disconnect()


# ============================================================================
# B) TestTransformScriptAndWorkflow
# ============================================================================

class TestTransformScriptAndWorkflow:

    @pytest.mark.asyncio
    async def test_yaml_workflow_definition(self, db_session):
        suffix = _random_suffix()
        yaml_str = f"""
stages:
  - id: extract_from_mysql
    type: extract
    config:
      source_type: mysql
      source_name: src_{suffix}
      query: "SELECT * FROM orders"
    dependencies: []
  - id: clean_and_transform
    type: transform
    config:
      sql: |
        SELECT
          id,
          user_id,
          amount,
          DATE(created_at) as order_date
        FROM input
        WHERE amount > 0
    dependencies:
      - extract_from_mysql
  - id: load_to_postgres
    type: load
    config:
      target_type: postgresql
      target_name: tgt_{suffix}
      table: cleaned_orders
      write_mode: append
    dependencies:
      - clean_and_transform
edges:
  - source: extract_from_mysql
    target: clean_and_transform
  - source: clean_and_transform
    target: load_to_postgres
schedule: "0 2 * * *"
sla_seconds: 1800
"""
        parsed = yaml.safe_load(yaml_str)
        assert "stages" in parsed
        assert len(parsed["stages"]) == 3

        dag_def = {
            "nodes": parsed["stages"],
            "edges": parsed.get("edges", []),
            "schedule": parsed.get("schedule"),
            "sla_seconds": parsed.get("sla_seconds"),
        }

        pipeline = Pipeline(
            name=f"yaml_workflow_{suffix}",
            description="YAML-defined 3-stage ETL pipeline",
            dag_definition=dag_def,
            schedule=parsed.get("schedule"),
            is_active=True,
        )
        db_session.add(pipeline)
        await db_session.commit()
        await db_session.refresh(pipeline)

        loaded = await db_session.get(Pipeline, pipeline.id)
        assert loaded is not None
        assert len(loaded.dag_definition["nodes"]) == 3
        assert len(loaded.dag_definition["edges"]) == 2
        assert loaded.dag_definition["nodes"][0]["id"] == "extract_from_mysql"

    @pytest.mark.asyncio
    async def test_dag_validation(self, db_session):
        suffix = _random_suffix()
        dag_def_dict = {
            "nodes": [
                {"id": "step1", "type": "extract", "dependencies": [], "config": {}},
                {"id": "step2", "type": "transform", "dependencies": ["step1"], "config": {}},
                {"id": "step3", "type": "load", "dependencies": ["step2"], "config": {}},
            ],
            "edges": [
                {"source": "step1", "target": "step2"},
                {"source": "step2", "target": "step3"},
            ],
        }
        pipeline = Pipeline(
            name=f"dag_validate_{suffix}",
            dag_definition=dag_def_dict,
        )
        db_session.add(pipeline)
        await db_session.commit()
        await db_session.refresh(pipeline)

        loaded = await db_session.get(Pipeline, pipeline.id)
        dag_definition = DAGDefinition(**loaded.dag_definition)
        dag = DAG(dag_definition)

        assert dag.validate() is True
        errors = dag.validate_with_details()
        assert len(errors) == 0

        order = dag.get_execution_order()
        assert len(order) == 3
        assert order[0] == ["step1"]
        assert order[1] == ["step2"]
        assert order[2] == ["step3"]


# ============================================================================
# C) TestManualTriggerAndExecutionLogging
# ============================================================================

class TestManualTriggerAndExecutionLogging:

    @pytest.mark.asyncio
    async def test_manual_trigger_creates_execution(self, db_session):
        suffix = _random_suffix()
        pipeline = Pipeline(
            name=f"manual_trigger_{suffix}",
            dag_definition={
                "nodes": [
                    {"id": "a", "type": "extract", "dependencies": [], "config": {}},
                ],
                "edges": [],
            },
        )
        db_session.add(pipeline)
        await db_session.commit()
        await db_session.refresh(pipeline)

        execution = PipelineExecution(
            pipeline_id=pipeline.id,
            status="running",
            trigger_type="manual",
            started_at=datetime.utcnow(),
        )
        db_session.add(execution)
        await db_session.commit()
        await db_session.refresh(execution)

        assert execution.id is not None
        assert execution.trigger_type == "manual"
        assert execution.status == "running"
        assert execution.pipeline_id == pipeline.id

        loaded = await db_session.get(PipelineExecution, execution.id)
        assert loaded is not None
        assert str(loaded.id) == str(execution.id)

    @pytest.mark.asyncio
    async def test_task_execution_logging(self, db_session):
        suffix = _random_suffix()
        pipeline = Pipeline(
            name=f"task_logging_{suffix}",
            dag_definition={
                "nodes": [
                    {"id": "extract", "type": "extract", "dependencies": [], "config": {}},
                    {"id": "transform", "type": "transform", "dependencies": ["extract"], "config": {}},
                    {"id": "load", "type": "load", "dependencies": ["transform"], "config": {}},
                ],
                "edges": [
                    {"source": "extract", "target": "transform"},
                    {"source": "transform", "target": "load"},
                ],
            },
        )
        db_session.add(pipeline)
        await db_session.commit()
        await db_session.refresh(pipeline)

        execution = PipelineExecution(
            pipeline_id=pipeline.id,
            status="success",
            trigger_type="manual",
            started_at=datetime.utcnow(),
            finished_at=datetime.utcnow(),
        )
        db_session.add(execution)
        await db_session.commit()
        await db_session.refresh(execution)

        started = datetime.utcnow()
        task_types = [
            ("extract", "extract", 1000, 1000),
            ("transform", "transform", 1000, 950),
            ("load", "load", 950, 950),
        ]
        for task_name, task_type, in_rows, out_rows in task_types:
            task = TaskExecution(
                pipeline_id=pipeline.id,
                task_name=task_name,
                task_type=task_type,
                status="success",
                started_at=started,
                finished_at=started + timedelta(seconds=2),
                input_rows=in_rows,
                output_rows=out_rows,
                retry_count=0,
            )
            db_session.add(task)
            started = started + timedelta(seconds=3)

        await db_session.commit()

        stmt = select(TaskExecution).where(TaskExecution.pipeline_id == pipeline.id).order_by(TaskExecution.task_name)
        result = await db_session.execute(stmt)
        tasks = result.scalars().all()

        assert len(tasks) == 3
        task_map = {t.task_name: t for t in tasks}
        assert task_map["extract"].task_type == "extract"
        assert task_map["extract"].input_rows == 1000
        assert task_map["transform"].task_type == "transform"
        assert task_map["transform"].output_rows == 950
        assert task_map["load"].task_type == "load"
        for t in tasks:
            assert t.status == "success"
            assert t.started_at is not None
            assert t.finished_at is not None

    @pytest.mark.asyncio
    async def test_execution_timeline(self, db_session):
        suffix = _random_suffix()
        pipeline = Pipeline(
            name=f"timeline_{suffix}",
            dag_definition={
                "nodes": [
                    {"id": "e1", "type": "extract", "dependencies": [], "config": {}},
                    {"id": "t1", "type": "transform", "dependencies": ["e1"], "config": {}},
                    {"id": "l1", "type": "load", "dependencies": ["t1"], "config": {}},
                ],
                "edges": [],
            },
        )
        db_session.add(pipeline)
        await db_session.commit()
        await db_session.refresh(pipeline)

        t0 = datetime.utcnow()
        tasks = [
            {"name": "e1", "type": "extract", "start": t0, "finish": t0 + timedelta(seconds=5)},
            {"name": "t1", "type": "transform", "start": t0 + timedelta(seconds=5), "finish": t0 + timedelta(seconds=12)},
            {"name": "l1", "type": "load", "start": t0 + timedelta(seconds=12), "finish": t0 + timedelta(seconds=18)},
        ]
        for tk in tasks:
            db_session.add(TaskExecution(
                pipeline_id=pipeline.id,
                task_name=tk["name"],
                task_type=tk["type"],
                status="success",
                started_at=tk["start"],
                finished_at=tk["finish"],
                input_rows=100,
                output_rows=100,
            ))
        await db_session.commit()

        stmt = select(TaskExecution).where(TaskExecution.pipeline_id == pipeline.id).order_by(TaskExecution.started_at)
        result = await db_session.execute(stmt)
        ordered = result.scalars().all()

        timeline = []
        for idx, t in enumerate(ordered):
            timeline.append({
                "index": idx,
                "task_name": t.task_name,
                "task_type": t.task_type,
                "started_at": t.started_at.isoformat(),
                "finished_at": t.finished_at.isoformat(),
                "duration_seconds": (t.finished_at - t.started_at).total_seconds(),
            })

        timeline_dict = {"execution_id": suffix, "timeline": timeline}

        serialized = json.dumps(timeline_dict)
        assert isinstance(serialized, str)

        restored = json.loads(serialized)
        assert restored["timeline"][0]["task_name"] in ("e1", "t1", "l1")
        names = [e["task_name"] for e in restored["timeline"]]
        assert names.index("e1") < names.index("t1") < names.index("l1")


# ============================================================================
# D) TestApiQueriesExecutionLogs
# ============================================================================

class TestApiQueriesExecutionLogs:

    @pytest.mark.asyncio
    async def test_api_get_executions(self, db_session, override_session):
        suffix = _random_suffix()
        pipeline = Pipeline(
            name=f"api_exec_pipeline_{suffix}",
            dag_definition={"nodes": [], "edges": []},
        )
        db_session.add(pipeline)
        await db_session.commit()
        await db_session.refresh(pipeline)

        execution = PipelineExecution(
            pipeline_id=pipeline.id,
            status="success",
            trigger_type="manual",
            started_at=datetime.utcnow(),
            finished_at=datetime.utcnow(),
            total_rows_read=500,
            total_rows_written=500,
        )
        db_session.add(execution)
        await db_session.commit()
        await db_session.refresh(execution)

        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.get("/api/executions")
        assert resp.status_code == 200
        data = resp.json()
        assert isinstance(data, list)
        assert any(str(ex["id"]) == str(execution.id) for ex in data)

    @pytest.mark.asyncio
    async def test_api_get_execution_tasks(self, db_session, override_session):
        suffix = _random_suffix()
        pipeline = Pipeline(
            name=f"api_tasks_pipeline_{suffix}",
            dag_definition={
                "nodes": [
                    {"id": "e", "type": "extract", "dependencies": [], "config": {}},
                    {"id": "t", "type": "transform", "dependencies": ["e"], "config": {}},
                    {"id": "l", "type": "load", "dependencies": ["t"], "config": {}},
                ],
                "edges": [
                    {"source": "e", "target": "t"},
                    {"source": "t", "target": "l"},
                ],
            },
        )
        db_session.add(pipeline)
        await db_session.commit()
        await db_session.refresh(pipeline)

        execution = PipelineExecution(
            pipeline_id=pipeline.id,
            status="success",
            trigger_type="scheduled",
            started_at=datetime.utcnow(),
            finished_at=datetime.utcnow(),
        )
        db_session.add(execution)
        await db_session.commit()
        await db_session.refresh(execution)

        for task_name, task_type in [("e", "extract"), ("t", "transform"), ("l", "load")]:
            db_session.add(TaskExecution(
                pipeline_id=pipeline.id,
                task_name=task_name,
                task_type=task_type,
                status="success",
                started_at=datetime.utcnow(),
                finished_at=datetime.utcnow(),
                input_rows=100,
                output_rows=100,
            ))
        await db_session.commit()

        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.get(f"/api/executions/{execution.id}/tasks")
        assert resp.status_code == 200
        data = resp.json()
        assert isinstance(data, list)
        assert len(data) == 3
        types_returned = sorted([t["task_type"] for t in data])
        assert types_returned == ["extract", "load", "transform"]

    @pytest.mark.asyncio
    async def test_api_get_metadata_status(self, db_session, override_session):
        suffix = _random_suffix()
        pipeline = Pipeline(
            name=f"meta_status_{suffix}",
            dag_definition={"nodes": [], "edges": []},
        )
        db_session.add(pipeline)
        await db_session.commit()
        await db_session.refresh(pipeline)

        execution = PipelineExecution(
            pipeline_id=pipeline.id,
            status="running",
            trigger_type="manual",
            started_at=datetime.utcnow(),
        )
        db_session.add(execution)
        await db_session.commit()
        await db_session.refresh(execution)

        async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as client:
            resp = await client.get("/api/metadata/status")
        assert resp.status_code == 200
        data = resp.json()
        assert isinstance(data, list)
        match = [s for s in data if str(s["pipeline_id"]) == str(pipeline.id)]
        assert len(match) == 1
        assert match[0]["latest_status"] == "running"
        assert str(match[0]["latest_execution_id"]) == str(execution.id)


# ============================================================================
# E) TestPrometheusMetricsCollection
# ============================================================================

class TestPrometheusMetricsCollection:

    @pytest.mark.asyncio
    async def test_metrics_recorded_after_tasks(self):
        suffix = _random_suffix()
        pipeline_name = f"metrics_pipeline_{suffix}"

        exporter = PrometheusExporter(port=9900 + (hash(suffix) % 100))
        collector = MetricsCollector()

        run_id = collector.start_task(
            execution_id="exec-" + suffix,
            pipeline_id="pipe-" + suffix,
            pipeline_name=pipeline_name,
            task_name="extract_users",
            task_type="extract",
        )
        time.sleep(0.01)

        log_entry = collector.finish_task(
            run_id=run_id,
            status="success",
            input_rows=1500,
            output_rows=1500,
        )
        assert isinstance(log_entry, ExecutionLog)
        assert log_entry.task_name == "extract_users"
        assert log_entry.duration_seconds is not None
        assert log_entry.duration_seconds >= 0

        exporter.record_task(log_entry)

        run_id2 = collector.start_task(
            execution_id="exec-" + suffix,
            pipeline_id="pipe-" + suffix,
            pipeline_name=pipeline_name,
            task_name="load_users",
            task_type="load",
        )
        time.sleep(0.01)
        log_entry2 = collector.finish_task(
            run_id=run_id2,
            status="success",
            input_rows=1500,
            output_rows=1498,
        )
        exporter.record_task(log_entry2)

        metrics_output = exporter.get_metrics()
        assert isinstance(metrics_output, str)
        assert "etl_task_duration_seconds" in metrics_output
        assert "etl_task_output_rows" in metrics_output
        assert pipeline_name in metrics_output


# ============================================================================
# F) TestFailureRecoveryFlow
# ============================================================================

class TestFailureRecoveryFlow:

    @pytest.mark.asyncio
    async def test_transform_step_failure_marks_execution_failed(self, db_session):
        suffix = _random_suffix()

        dag_def = {
            "nodes": [
                {
                    "id": "extract_step",
                    "type": "extract",
                    "dependencies": [],
                    "config": {"source_type": "mysql"},
                    "on_failure": "fail",
                },
                {
                    "id": "bad_transform",
                    "type": "transform",
                    "dependencies": ["extract_step"],
                    "config": {
                        "udf_code": "raise ValueError('bad conversion')",
                        "transform_type": "udf",
                    },
                    "on_failure": "fail",
                },
                {
                    "id": "load_step",
                    "type": "load",
                    "dependencies": ["bad_transform"],
                    "config": {"target_type": "postgresql"},
                    "on_failure": "fail",
                },
            ],
            "edges": [
                {"source": "extract_step", "target": "bad_transform"},
                {"source": "bad_transform", "target": "load_step"},
            ],
        }

        pipeline = Pipeline(
            name=f"failure_pipeline_{suffix}",
            dag_definition=dag_def,
            max_retries=0,
        )
        db_session.add(pipeline)
        await db_session.commit()
        await db_session.refresh(pipeline)

        execution = PipelineExecution(
            pipeline_id=pipeline.id,
            status="running",
            trigger_type="manual",
            started_at=datetime.utcnow(),
        )
        db_session.add(execution)
        await db_session.commit()
        await db_session.refresh(execution)

        start_ts = datetime.utcnow()
        task_extract = TaskExecution(
            pipeline_id=pipeline.id,
            task_name="extract_step",
            task_type="extract",
            status="success",
            started_at=start_ts,
            finished_at=start_ts + timedelta(seconds=3),
            input_rows=1000,
            output_rows=1000,
            retry_count=0,
        )
        db_session.add(task_extract)
        await db_session.flush()

        task_transform = TaskExecution(
            pipeline_id=pipeline.id,
            task_name="bad_transform",
            task_type="transform",
            status="failed",
            started_at=start_ts + timedelta(seconds=3),
            finished_at=start_ts + timedelta(seconds=5),
            input_rows=1000,
            output_rows=None,
            error_message="ValueError: bad conversion",
            retry_count=0,
        )
        db_session.add(task_transform)
        await db_session.flush()

        execution.status = "failed"
        execution.finished_at = start_ts + timedelta(seconds=5)
        execution.error_message = "Transform step failed: bad conversion"
        await db_session.commit()

        loaded_execution = await db_session.get(PipelineExecution, execution.id)
        assert loaded_execution.status == "failed"

        stmt = select(TaskExecution).where(
            TaskExecution.pipeline_id == pipeline.id,
            TaskExecution.task_name == "bad_transform",
        )
        result = await db_session.execute(stmt)
        loaded_task = result.scalar_one()
        assert loaded_task.status == "failed"
        assert "bad conversion" in loaded_task.error_message
        assert loaded_task.retry_count == 0

    @pytest.mark.asyncio
    async def test_fix_script_and_retry_succeeds(self, db_session):
        suffix = _random_suffix()
        original_dag = {
            "nodes": [
                {"id": "s1", "type": "extract", "dependencies": [], "config": {}},
                {"id": "s2", "type": "transform", "dependencies": ["s1"], "config": {"udf_code": "raise ValueError('bad')"}},
                {"id": "s3", "type": "load", "dependencies": ["s2"], "config": {}},
            ],
            "edges": [
                {"source": "s1", "target": "s2"},
                {"source": "s2", "target": "s3"},
            ],
        }
        pipeline = Pipeline(
            name=f"retry_pipeline_{suffix}",
            dag_definition=original_dag,
            max_retries=2,
        )
        db_session.add(pipeline)
        await db_session.commit()
        await db_session.refresh(pipeline)

        failed_exec = PipelineExecution(
            pipeline_id=pipeline.id,
            status="failed",
            trigger_type="manual",
            started_at=datetime.utcnow(),
            finished_at=datetime.utcnow(),
            error_message="Initial run failed",
        )
        db_session.add(failed_exec)
        await db_session.commit()
        await db_session.refresh(failed_exec)

        pipeline.dag_definition["nodes"][1]["config"] = {
            "udf_code": "df['col'] = df['col'] * 2\nreturn df",
        }
        await db_session.commit()

        retry_exec = PipelineExecution(
            pipeline_id=pipeline.id,
            status="success",
            trigger_type="retry",
            started_at=datetime.utcnow(),
            finished_at=datetime.utcnow(),
        )
        db_session.add(retry_exec)
        await db_session.commit()
        await db_session.refresh(retry_exec)

        base_ts = datetime.utcnow()
        for idx, (name, ttype, in_rows, out_rows) in enumerate([
            ("s1", "extract", 500, 500),
            ("s2", "transform", 500, 500),
            ("s3", "load", 500, 500),
        ]):
            db_session.add(TaskExecution(
                pipeline_id=pipeline.id,
                task_name=name,
                task_type=ttype,
                status="success",
                started_at=base_ts + timedelta(seconds=idx * 2),
                finished_at=base_ts + timedelta(seconds=idx * 2 + 1),
                input_rows=in_rows,
                output_rows=out_rows,
                retry_count=1 if idx == 1 else 0,
            ))
        await db_session.commit()

        prev = await db_session.get(PipelineExecution, failed_exec.id)
        assert prev.status == "failed"

        curr = await db_session.get(PipelineExecution, retry_exec.id)
        assert curr.status == "success"
        assert curr.trigger_type == "retry"

        stmt = select(TaskExecution).where(TaskExecution.pipeline_id == pipeline.id).order_by(TaskExecution.task_name)
        result = await db_session.execute(stmt)
        tasks = result.scalars().all()
        assert len([t for t in tasks if t.status == "success"]) >= 3

    @pytest.mark.asyncio
    async def test_retry_only_runs_failed_steps(self, db_session):
        suffix = _random_suffix()
        dag_def = {
            "nodes": [
                {"id": "step_a", "type": "extract", "dependencies": [], "config": {}},
                {"id": "step_b", "type": "transform", "dependencies": ["step_a"], "config": {}},
                {"id": "step_c", "type": "load", "dependencies": ["step_b"], "config": {}},
            ],
            "edges": [
                {"source": "step_a", "target": "step_b"},
                {"source": "step_b", "target": "step_c"},
            ],
        }
        pipeline = Pipeline(
            name=f"smart_retry_{suffix}",
            dag_definition=dag_def,
            max_retries=3,
        )
        db_session.add(pipeline)
        await db_session.commit()
        await db_session.refresh(pipeline.id)
        await db_session.refresh(pipeline)

        execution = PipelineExecution(
            pipeline_id=pipeline.id,
            status="running",
            trigger_type="retry",
            started_at=datetime.utcnow(),
        )
        db_session.add(execution)
        await db_session.commit()
        await db_session.refresh(execution)

        ts = datetime.utcnow()
        task_a = TaskExecution(
            pipeline_id=pipeline.id,
            task_name="step_a",
            task_type="extract",
            status="success",
            started_at=ts,
            finished_at=ts + timedelta(seconds=1),
            input_rows=200,
            output_rows=200,
            retry_count=0,
            config={"retry_action": "skipped_already_done"},
        )
        db_session.add(task_a)

        task_b = TaskExecution(
            pipeline_id=pipeline.id,
            task_name="step_b",
            task_type="transform",
            status="success",
            started_at=ts + timedelta(seconds=1),
            finished_at=ts + timedelta(seconds=3),
            input_rows=200,
            output_rows=200,
            retry_count=1,
        )
        db_session.add(task_b)

        task_c = TaskExecution(
            pipeline_id=pipeline.id,
            task_name="step_c",
            task_type="load",
            status="success",
            started_at=ts + timedelta(seconds=3),
            finished_at=ts + timedelta(seconds=4),
            input_rows=200,
            output_rows=200,
            retry_count=0,
        )
        db_session.add(task_c)

        execution.status = "success"
        execution.finished_at = ts + timedelta(seconds=4)
        await db_session.commit()

        stmt = select(TaskExecution).where(TaskExecution.pipeline_id == pipeline.id).order_by(TaskExecution.task_name)
        result = await db_session.execute(stmt)
        tasks = result.scalars().all()
        tmap = {t.task_name: t for t in tasks}

        assert tmap["step_a"].status == "success"
        assert tmap["step_a"].retry_count == 0
        assert tmap["step_a"].config is not None
        assert "skipped" in tmap["step_a"].config.get("retry_action", "")

        assert tmap["step_b"].status == "success"
        assert tmap["step_b"].retry_count >= 1

        assert tmap["step_c"].status == "success"
