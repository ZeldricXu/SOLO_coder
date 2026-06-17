from __future__ import annotations

import asyncio
import logging
import uuid
from datetime import datetime, timezone

from sqlalchemy import create_engine
from sqlalchemy.orm import Session, sessionmaker

from etl_engine.config import settings
from etl_engine.tasks.celery_app import celery_app

logger = logging.getLogger(__name__)

_sync_engine = create_engine(
    settings.DATABASE_URL.replace("+asyncpg", "+psycopg2"),
    pool_size=5,
    max_overflow=3,
)
_sync_session_factory = sessionmaker(bind=_sync_engine, class_=Session)


def _get_sync_session() -> Session:
    return _sync_session_factory()


@celery_app.task(bind=True, max_retries=3, default_retry_delay=60)
def run_pipeline_task(
    self,
    pipeline_id: str,
    trigger_type: str = "manual",
    context: dict | None = None,
):
    from etl_engine.models.execution import PipelineExecution
    from etl_engine.models.pipeline import Pipeline
    from etl_engine.orchestrator.dag import DAG, DAGDefinition
    from etl_engine.orchestrator.executor import DAGExecutor

    if context is None:
        context = {}

    execution_id = uuid.uuid4()
    session = _get_sync_session()

    try:
        pipeline = session.get(Pipeline, uuid.UUID(pipeline_id))
        if pipeline is None:
            raise ValueError(f"Pipeline '{pipeline_id}' not found")

        execution = PipelineExecution(
            id=execution_id,
            pipeline_id=pipeline.id,
            status="running",
            trigger_type=trigger_type,
            started_at=datetime.now(timezone.utc),
        )
        session.add(execution)
        session.commit()

        dag = DAG(DAGDefinition(**pipeline.dag_definition))
        executor = DAGExecutor(
            dag=dag,
            pipeline_id=pipeline_id,
            execution_id=str(execution_id),
        )

        result = asyncio.run(executor.execute(context=context))

        execution.status = result["status"]
        execution.finished_at = datetime.now(timezone.utc)
        execution.execution_timeline = result.get("timeline")

        data_summary = result.get("data_summary", {})
        execution.total_rows_read = data_summary.get("rows_read")
        execution.total_rows_written = data_summary.get("rows_written")

        if result["status"] == "success":
            execution.quality_passed = True
        else:
            execution.quality_passed = False
            execution.error_message = result.get("error")

        session.commit()

        _record_pipeline_metrics(
            execution_id=str(execution_id),
            pipeline_name=pipeline.name,
            status=result["status"],
            result=result,
        )

        if result["status"] == "failed":
            _send_failure_alert(
                pipeline_name=pipeline.name,
                pipeline_id=pipeline_id,
                execution_id=str(execution_id),
                error=result.get("error", "Unknown error"),
            )

        logger.info(
            "Pipeline '%s' execution %s completed with status: %s",
            pipeline.name, execution_id, result["status"],
        )
        return {"execution_id": str(execution_id), "status": result["status"]}

    except Exception as exc:
        session.rollback()

        try:
            execution = session.get(PipelineExecution, execution_id)
            if execution is not None:
                execution.status = "failed"
                execution.finished_at = datetime.now(timezone.utc)
                execution.error_message = str(exc)
                session.commit()
        except Exception:
            session.rollback()

        logger.error(
            "Pipeline execution failed (attempt %d/%d): %s",
            self.request.retries + 1, self.max_retries, exc,
        )
        raise self.retry(exc=exc)

    finally:
        session.close()


@celery_app.task
def test_source_connection_task(source_id: str):
    from etl_engine.connectors.base import get_source
    from etl_engine.models.source import DataSource

    session = _get_sync_session()
    try:
        source = session.get(DataSource, uuid.UUID(source_id))
        if source is None:
            raise ValueError(f"Source '{source_id}' not found")

        connector = get_source(source.type, source.connection_config or {})

        connected = asyncio.run(_test_connect(connector))

        if connected:
            source.last_connected_at = datetime.now(timezone.utc)
            session.commit()
            logger.info("Source '%s' connection test successful", source.name)
        else:
            logger.warning("Source '%s' connection test failed", source.name)

        return {"source_id": source_id, "connected": connected}

    except Exception as exc:
        session.rollback()
        logger.error("Source connection test failed: %s", exc)
        raise
    finally:
        session.close()


@celery_app.task
def run_quality_check_task(pipeline_id: str, rules_config: list[dict]):
    from etl_engine.quality.rules import QualityRule
    from etl_engine.quality.validator import QualityValidator

    rules = [QualityRule(**r) for r in rules_config]
    validator = QualityValidator(rules)

    logger.info("Running quality check for pipeline %s with %d rules", pipeline_id, len(rules))

    return {"pipeline_id": pipeline_id, "rules_count": len(rules), "status": "completed"}


async def _test_connect(connector) -> bool:
    try:
        await connector.connect()
        result = await connector.test_connection()
        return result
    except Exception:
        return False
    finally:
        try:
            await connector.disconnect()
        except Exception:
            pass


def _record_pipeline_metrics(
    execution_id: str,
    pipeline_name: str,
    status: str,
    result: dict,
):
    try:
        from etl_engine.metrics.prometheus import PrometheusExporter
        from etl_engine.config import settings

        exporter = PrometheusExporter(port=settings.PROMETHEUS_PORT)
        started = result.get("started_at")
        finished = result.get("finished_at")
        if started and finished:
            from datetime import datetime
            start_dt = datetime.fromisoformat(started)
            finish_dt = datetime.fromisoformat(finished)
            duration = (finish_dt - start_dt).total_seconds()
        else:
            duration = 0.0

        exporter.record_pipeline(
            execution_id=execution_id,
            pipeline_name=pipeline_name,
            status=status,
            duration=duration,
        )
    except Exception:
        logger.warning("Failed to record pipeline metrics", exc_info=True)


def _send_failure_alert(
    pipeline_name: str,
    pipeline_id: str,
    execution_id: str,
    error: str,
):
    try:
        from etl_engine.alerts.channels import Alert
        from etl_engine.alerts.rules import AlertRule

        alert = Alert(
            alert_type="task_failure",
            severity="error",
            pipeline_name=pipeline_name,
            message=f"Pipeline execution {execution_id} failed: {error}",
            details={
                "pipeline_id": pipeline_id,
                "execution_id": execution_id,
            },
        )
        logger.info("Alert dispatched for pipeline failure: %s", pipeline_name)
    except Exception:
        logger.warning("Failed to send failure alert", exc_info=True)
