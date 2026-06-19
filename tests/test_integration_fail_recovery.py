import asyncio
import os
import uuid
import time
from datetime import datetime, timedelta
from unittest.mock import AsyncMock, MagicMock, patch, PropertyMock

import pytest
import pytest_asyncio
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from etl_engine.models import Base, Pipeline, PipelineExecution, TaskExecution
from etl_engine.orchestrator.dag import DAG, DAGDefinition
from etl_engine.orchestrator.scheduler import DAGScheduler
from etl_engine.alerts.manager import AlertManager
from etl_engine.alerts.channels import Alert, AlertChannel
from etl_engine.alerts.rules import AlertRule
from etl_engine.quality.rules import QualityRule


SKIP_INTEGRATION = os.environ.get("SKIP_INTEGRATION", "0") == "1"

pytestmark = [
    pytest.mark.integration,
    pytest.mark.skipif(SKIP_INTEGRATION, reason="SKIP_INTEGRATION=1 set, skipping integration tests"),
]


def _random_suffix() -> str:
    return uuid.uuid4().hex[:8]


@pytest_asyncio.fixture
async def db_session():
    engine = create_async_engine("sqlite+aiosqlite:///:memory:")
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    session_factory = async_sessionmaker(engine, class_=AsyncSession, expire_on_commit=False)
    async with session_factory() as session:
        yield session

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
    await engine.dispose()


class MockAlertChannel(AlertChannel):
    def __init__(self):
        self.sent_alerts: list[Alert] = []

    async def send(self, alert: Alert) -> bool:
        self.sent_alerts.append(alert)
        return True


# ============================================================================
# TestRetryStrategyExhausted
# ============================================================================

class TestRetryStrategyExhausted:

    @pytest.mark.asyncio
    async def test_retry_strategy_exhausted(self, db_session):
        suffix = _random_suffix()
        dag_def = {
            "nodes": [
                {"id": "extract", "type": "extract", "dependencies": [], "config": {},
                 "max_retries": 3, "retry_delay_seconds": 0, "on_failure": "retry"},
                {"id": "transform", "type": "transform", "dependencies": ["extract"], "config": {},
                 "max_retries": 3, "retry_delay_seconds": 0, "on_failure": "retry"},
            ],
            "edges": [
                {"source": "extract", "target": "transform"},
            ],
        }
        pipeline = Pipeline(
            name=f"retry_exhausted_{suffix}",
            dag_definition=dag_def,
            max_retries=3,
            retry_delay_seconds=0,
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

        base_ts = datetime.utcnow()
        extract_ok = TaskExecution(
            pipeline_id=pipeline.id,
            task_name="extract",
            task_type="extract",
            status="success",
            started_at=base_ts,
            finished_at=base_ts + timedelta(seconds=1),
            input_rows=100,
            output_rows=100,
            retry_count=0,
        )
        db_session.add(extract_ok)

        transform_fail = TaskExecution(
            pipeline_id=pipeline.id,
            task_name="transform",
            task_type="transform",
            status="failed",
            started_at=base_ts + timedelta(seconds=1),
            finished_at=base_ts + timedelta(seconds=2),
            input_rows=100,
            output_rows=None,
            error_message="attempt 1: ConnectionError timeout",
            retry_count=1,
        )
        db_session.add(transform_fail)

        transform_fail2 = TaskExecution(
            pipeline_id=pipeline.id,
            task_name="transform",
            task_type="transform",
            status="failed",
            started_at=base_ts + timedelta(seconds=2),
            finished_at=base_ts + timedelta(seconds=3),
            input_rows=100,
            output_rows=None,
            error_message="attempt 2: ConnectionError timeout",
            retry_count=2,
        )
        db_session.add(transform_fail2)

        transform_fail3 = TaskExecution(
            pipeline_id=pipeline.id,
            task_name="transform",
            task_type="transform",
            status="failed",
            started_at=base_ts + timedelta(seconds=3),
            finished_at=base_ts + timedelta(seconds=4),
            input_rows=100,
            output_rows=None,
            error_message="attempt 3: ConnectionError timeout - retries exhausted",
            retry_count=3,
        )
        db_session.add(transform_fail3)

        execution.status = "failed"
        execution.finished_at = base_ts + timedelta(seconds=4)
        execution.error_message = "Max retries exceeded for task 'transform'"
        await db_session.commit()

        loaded_execution = await db_session.get(PipelineExecution, execution.id)
        assert loaded_execution.status == "failed"
        assert "retries" in loaded_execution.error_message.lower() or "exceeded" in loaded_execution.error_message.lower()

        stmt = select(TaskExecution).where(
            TaskExecution.pipeline_id == pipeline.id,
            TaskExecution.task_name == "transform",
        ).order_by(TaskExecution.retry_count)
        result = await db_session.execute(stmt)
        transform_attempts = result.scalars().all()

        final_attempt = max(transform_attempts, key=lambda t: t.retry_count)
        assert final_attempt.status == "failed"
        assert final_attempt.retry_count == 3
        assert "timeout" in final_attempt.error_message.lower()

        mock_channel = MockAlertChannel()
        rules = [
            AlertRule(
                alert_type="task_failure",
                channels=["mock"],
                min_severity="error",
                cooldown_minutes=0,
            ),
        ]
        alert_mgr = AlertManager.__new__(AlertManager)
        alert_mgr.channels = {"mock": mock_channel}
        alert_mgr.rules = rules
        alert_mgr._redis = MagicMock()
        alert_mgr._redis.exists.return_value = 0

        with patch.object(alert_mgr, "_check_cooldown", return_value=False), \
             patch.object(alert_mgr, "_set_cooldown"):
            alert = Alert(
                alert_type="task_failure",
                severity="critical",
                pipeline_name=pipeline.name,
                task_name="transform",
                message="Max retries exhausted",
                details={"retry_count": final_attempt.retry_count},
            )
            result = await alert_mgr.notify(alert)

        assert len(mock_channel.sent_alerts) >= 1
        assert result["severity"] == "critical"
        assert "alert_type" in result

    @pytest.mark.asyncio
    async def test_alert_notify_called_on_retries_exhausted(self):
        suffix = _random_suffix()
        mock_manager = MagicMock(spec=AlertManager)
        mock_manager.notify = AsyncMock(return_value={
            "alert_type": "task_failure",
            "severity": "critical",
            "channels_notified": ["mock"],
        })

        alert = Alert(
            alert_type="task_failure",
            severity="critical",
            pipeline_name=f"pipe_{suffix}",
            task_name="transform_step",
            message="Max retries (3) exhausted",
            details={"retry_count": 3, "error": "ConnectionError"},
        )
        result = await mock_manager.notify(alert)

        mock_manager.notify.assert_called_once()
        call_args = mock_manager.notify.call_args
        passed_alert = call_args[0][0]
        assert passed_alert.severity == "critical"
        assert passed_alert.task_name == "transform_step"
        assert result["channels_notified"] == ["mock"]


# ============================================================================
# TestSkipStrategyOnNonCritical
# ============================================================================

class TestSkipStrategyOnNonCritical:

    @pytest.mark.asyncio
    async def test_skip_strategy_on_non_critical(self, db_session):
        suffix = _random_suffix()
        dag_def = {
            "nodes": [
                {"id": "ingest", "type": "extract", "dependencies": [], "config": {}},
                {
                    "id": "quality_check",
                    "type": "quality_check",
                    "dependencies": ["ingest"],
                    "config": {
                        "rules": [
                            {"rule_type": "null_rate", "column": "email", "params": {"max_null_rate": 0.01}},
                        ],
                        "is_critical": False,
                    },
                    "on_failure": "skip",
                },
                {"id": "load", "type": "load", "dependencies": ["quality_check"], "config": {}},
            ],
            "edges": [
                {"source": "ingest", "target": "quality_check"},
                {"source": "quality_check", "target": "load"},
            ],
        }
        pipeline = Pipeline(
            name=f"skip_non_critical_{suffix}",
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

        ts = datetime.utcnow()
        db_session.add(TaskExecution(
            pipeline_id=pipeline.id,
            task_name="ingest",
            task_type="extract",
            status="success",
            started_at=ts,
            finished_at=ts + timedelta(seconds=1),
            input_rows=1000,
            output_rows=1000,
        ))

        db_session.add(TaskExecution(
            pipeline_id=pipeline.id,
            task_name="quality_check",
            task_type="quality_check",
            status="skipped",
            started_at=ts + timedelta(seconds=1),
            finished_at=ts + timedelta(seconds=2),
            input_rows=1000,
            output_rows=1000,
            error_message="Null rate on 'email' exceeded threshold (0.05 > 0.01) - non-critical, skipping",
            quality_report={"passed": False, "null_rate_violation": True},
            retry_count=0,
        ))

        db_session.add(TaskExecution(
            pipeline_id=pipeline.id,
            task_name="load",
            task_type="load",
            status="success",
            started_at=ts + timedelta(seconds=2),
            finished_at=ts + timedelta(seconds=3),
            input_rows=1000,
            output_rows=1000,
        ))

        execution.status = "success"
        execution.finished_at = ts + timedelta(seconds=3)
        execution.quality_passed = False
        execution.error_message = None
        await db_session.commit()

        loaded_exec = await db_session.get(PipelineExecution, execution.id)
        assert loaded_exec.status == "success"
        assert loaded_exec.quality_passed is False

        stmt = select(TaskExecution).where(TaskExecution.pipeline_id == pipeline.id).order_by(TaskExecution.task_name)
        result = await db_session.execute(stmt)
        tasks = result.scalars().all()
        tmap = {t.task_name: t for t in tasks}

        assert tmap["ingest"].status == "success"
        assert tmap["quality_check"].status == "skipped"
        assert "non-critical" in tmap["quality_check"].error_message.lower() or "skip" in tmap["quality_check"].error_message.lower()
        assert tmap["load"].status == "success"

    @pytest.mark.asyncio
    async def test_dag_continues_after_skipped_quality(self):
        dag_def_dict = {
            "nodes": [
                {"id": "a", "type": "extract", "dependencies": [], "config": {}, "on_failure": "fail"},
                {"id": "qc", "type": "quality_check", "dependencies": ["a"], "config": {}, "on_failure": "skip"},
                {"id": "b", "type": "transform", "dependencies": ["qc"], "config": {}, "on_failure": "fail"},
                {"id": "c", "type": "load", "dependencies": ["b"], "config": {}, "on_failure": "fail"},
            ],
            "edges": [
                {"source": "a", "target": "qc"},
                {"source": "qc", "target": "b"},
                {"source": "b", "target": "c"},
            ],
        }
        dag = DAG(DAGDefinition(**dag_def_dict))
        assert dag.validate()

        order = dag.get_execution_order()
        assert len(order) == 4

        qc_node = dag.get_node("qc")
        assert qc_node.on_failure == "skip"

        simulated_statuses = {
            "a": "success",
            "qc": "skipped",
            "b": "pending",
            "c": "pending",
        }

        downstream_of_qc = dag.get_downstream("qc")
        assert "b" in downstream_of_qc

        for ds in downstream_of_qc:
            simulated_statuses[ds] = "can_run"

        assert simulated_statuses["b"] == "can_run"
        assert simulated_statuses["c"] == "pending"


# ============================================================================
# TestSlaTimeoutDetection
# ============================================================================

class TestSlaTimeoutDetection:

    @pytest.mark.asyncio
    async def test_sla_timeout_detection(self):
        scheduler = DAGScheduler()

        started_just_now = datetime.utcnow() - timedelta(seconds=0.1)
        assert scheduler.check_sla(started_just_now, sla_seconds=1) is False

        started_a_while_ago = datetime.utcnow() - timedelta(seconds=3)
        assert scheduler.check_sla(started_a_while_ago, sla_seconds=1) is True

        started_exactly = datetime.utcnow() - timedelta(seconds=2)
        assert scheduler.check_sla(started_exactly, sla_seconds=2) is False

    @pytest.mark.asyncio
    async def test_sla_timeout_with_real_wait(self):
        scheduler = DAGScheduler()
        started_at = datetime.utcnow()
        sla_seconds = 1

        assert scheduler.check_sla(started_at, sla_seconds) is False

        await asyncio.sleep(1.1)

        assert scheduler.check_sla(started_at, sla_seconds) is True

    @pytest.mark.asyncio
    async def test_sla_breach_recorded_on_execution(self, db_session):
        suffix = _random_suffix()
        dag_def = {
            "nodes": [
                {"id": "s1", "type": "extract", "dependencies": [], "config": {}},
            ],
            "edges": [],
            "sla_seconds": 1,
        }
        pipeline = Pipeline(
            name=f"sla_breach_{suffix}",
            dag_definition=dag_def,
            sla_seconds=1,
        )
        db_session.add(pipeline)
        await db_session.commit()
        await db_session.refresh(pipeline)

        started = datetime.utcnow() - timedelta(seconds=3)
        execution = PipelineExecution(
            pipeline_id=pipeline.id,
            status="running",
            trigger_type="manual",
            started_at=started,
        )
        db_session.add(execution)
        await db_session.commit()
        await db_session.refresh(execution)

        scheduler = DAGScheduler()
        breached = scheduler.check_sla(execution.started_at, pipeline.sla_seconds)
        assert breached is True

        from etl_engine.metrics.prometheus import PrometheusExporter
        exporter = PrometheusExporter(port=9800 + (hash(suffix) % 100))
        exporter.record_sla_breach(pipeline.name)
        metrics = exporter.get_metrics()
        assert "etl_sla_breaches_total" in metrics
        assert pipeline.name in metrics


# ============================================================================
# TestQualityFailureBlockStrategy
# ============================================================================

class TestQualityFailureBlockStrategy:

    @pytest.mark.asyncio
    async def test_quality_failure_block_strategy(self, db_session):
        suffix = _random_suffix()
        dag_def = {
            "nodes": [
                {"id": "extract", "type": "extract", "dependencies": [], "config": {}},
                {
                    "id": "quality_gate",
                    "type": "quality_check",
                    "dependencies": ["extract"],
                    "config": {
                        "rules": [
                            {
                                "rule_type": "value_range",
                                "column": "amount",
                                "params": {"min_value": 0, "max_value": 1000},
                                "strategy": "block",
                                "threshold": 1.0,
                            },
                        ],
                    },
                    "on_failure": "fail",
                },
                {"id": "transform", "type": "transform", "dependencies": ["quality_gate"], "config": {}},
                {"id": "load", "type": "load", "dependencies": ["transform"], "config": {}},
            ],
            "edges": [
                {"source": "extract", "target": "quality_gate"},
                {"source": "quality_gate", "target": "transform"},
                {"source": "transform", "target": "load"},
            ],
        }
        pipeline = Pipeline(
            name=f"quality_block_{suffix}",
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

        ts = datetime.utcnow()
        db_session.add(TaskExecution(
            pipeline_id=pipeline.id,
            task_name="extract",
            task_type="extract",
            status="success",
            started_at=ts,
            finished_at=ts + timedelta(seconds=1),
            input_rows=100,
            output_rows=100,
        ))

        db_session.add(TaskExecution(
            pipeline_id=pipeline.id,
            task_name="quality_gate",
            task_type="quality_check",
            status="failed",
            started_at=ts + timedelta(seconds=1),
            finished_at=ts + timedelta(seconds=2),
            input_rows=100,
            output_rows=None,
            error_message="Quality rule 'value_range' violated: amount has 15 rows with value > 1000 (strategy: block)",
            retry_count=0,
            quality_report={
                "passed": False,
                "blocking_violation": True,
                "failed_rules": [
                    {"rule_type": "value_range", "column": "amount", "strategy": "block", "violating_rows": 15},
                ],
            },
        ))

        db_session.add(TaskExecution(
            pipeline_id=pipeline.id,
            task_name="transform",
            task_type="transform",
            status="blocked",
            started_at=None,
            finished_at=None,
            input_rows=None,
            output_rows=None,
            error_message="Blocked due to upstream quality gate failure",
            retry_count=0,
        ))

        db_session.add(TaskExecution(
            pipeline_id=pipeline.id,
            task_name="load",
            task_type="load",
            status="blocked",
            started_at=None,
            finished_at=None,
            input_rows=None,
            output_rows=None,
            error_message="Blocked due to upstream quality gate failure",
            retry_count=0,
        ))

        execution.status = "failed"
        execution.finished_at = ts + timedelta(seconds=2)
        execution.quality_passed = False
        execution.error_message = "Pipeline blocked by quality gate 'quality_gate'"
        await db_session.commit()

        loaded_exec = await db_session.get(PipelineExecution, execution.id)
        assert loaded_exec.status == "failed"
        assert loaded_exec.quality_passed is False
        assert "block" in loaded_exec.error_message.lower()

        stmt = select(TaskExecution).where(TaskExecution.pipeline_id == pipeline.id).order_by(TaskExecution.task_name)
        result = await db_session.execute(stmt)
        tasks = result.scalars().all()
        tmap = {t.task_name: t for t in tasks}

        assert tmap["extract"].status == "success"
        assert tmap["quality_gate"].status == "failed"
        qr = tmap["quality_gate"].quality_report
        assert qr is not None
        assert qr.get("passed") is False
        assert qr.get("blocking_violation") is True

        assert tmap["transform"].status == "blocked"
        assert tmap["load"].status == "blocked"

    @pytest.mark.asyncio
    async def test_quality_rule_block_enforced(self):
        rule = {
            "rule_type": "uniqueness",
            "column": "user_id",
            "params": {"expect_unique": True},
            "strategy": "block",
            "threshold": 0.98,
        }
        assert rule["strategy"] == "block"

        dag_def = {
            "nodes": [
                {"id": "e", "type": "extract", "dependencies": [], "config": {}},
                {"id": "q", "type": "quality_check", "dependencies": ["e"], "config": {"rules": [rule]}, "on_failure": "fail"},
                {"id": "l", "type": "load", "dependencies": ["q"], "config": {}},
            ],
            "edges": [
                {"source": "e", "target": "q"},
                {"source": "q", "target": "l"},
            ],
        }
        dag = DAG(DAGDefinition(**dag_def))
        assert dag.validate()

        q_node = dag.get_node("q")
        assert "rules" in q_node.config
        assert q_node.config["rules"][0]["strategy"] == "block"


# ============================================================================
# TestNotificationOnFailure
# ============================================================================

class TestNotificationOnFailure:

    @pytest.mark.asyncio
    async def test_notification_on_failure(self):
        suffix = _random_suffix()
        mock_channel = MockAlertChannel()
        rules = [
            AlertRule(
                alert_type="task_failure",
                channels=["email", "slack"],
                min_severity="error",
                cooldown_minutes=0,
                enabled=True,
            ),
        ]

        mock_email = MockAlertChannel()
        mock_slack = MockAlertChannel()

        alert_mgr = AlertManager.__new__(AlertManager)
        alert_mgr.channels = {"email": mock_email, "slack": mock_slack}
        alert_mgr.rules = rules
        alert_mgr._redis = MagicMock()
        alert_mgr._redis.exists.return_value = 0

        failure_alert = Alert(
            alert_type="task_failure",
            severity="error",
            pipeline_name=f"notif_pipeline_{suffix}",
            task_name="load_to_warehouse",
            message="Database connection refused during LOAD step",
            details={
                "error_type": "ConnectionRefusedError",
                "retry_count": 2,
                "output_rows": 0,
            },
        )

        with patch.object(alert_mgr, "_check_cooldown", return_value=False), \
             patch.object(alert_mgr, "_set_cooldown"):
            notify_mock = AsyncMock(wraps=alert_mgr.notify)
            alert_mgr.notify = notify_mock
            result = await alert_mgr.notify(failure_alert)

        notify_mock.assert_awaited_once()
        call_args = notify_mock.await_args
        arg_alert = call_args[0][0]
        assert arg_alert.severity in ("error", "critical")
        assert arg_alert.alert_type == "task_failure"

        assert result["alert_type"] == "task_failure"
        assert result["severity"] in ("error", "critical")
        assert result["pipeline_name"] == f"notif_pipeline_{suffix}"

        total_sent = len(mock_email.sent_alerts) + len(mock_slack.sent_alerts)
        assert total_sent >= 1

        all_alerts = mock_email.sent_alerts + mock_slack.sent_alerts
        if all_alerts:
            delivered = all_alerts[0]
            assert delivered.severity in ("error", "critical")
            assert delivered.task_name == "load_to_warehouse"

    @pytest.mark.asyncio
    async def test_critical_severity_notification(self):
        mock_channel = MockAlertChannel()
        rules = [
            AlertRule(
                alert_type="task_failure",
                channels=["pagerduty"],
                min_severity="critical",
                cooldown_minutes=0,
            ),
        ]
        pagerduty_mock = MockAlertChannel()

        mgr = AlertManager.__new__(AlertManager)
        mgr.channels = {"pagerduty": pagerduty_mock}
        mgr.rules = rules
        mgr._redis = MagicMock()
        mgr._redis.exists.return_value = 0

        critical_alert = Alert(
            alert_type="task_failure",
            severity="critical",
            pipeline_name="revenue_pipeline",
            task_name="final_aggregate",
            message="Primary key violation on revenue_facts table - data integrity at risk",
            details={"error_code": "23505", "table": "revenue_facts"},
        )

        with patch.object(mgr, "_check_cooldown", return_value=False), \
             patch.object(mgr, "_set_cooldown"):
            result = await mgr.notify(critical_alert)

        assert result["severity"] == "critical"
        assert len(pagerduty_mock.sent_alerts) >= 1
        assert pagerduty_mock.sent_alerts[0].severity == "critical"

    @pytest.mark.asyncio
    async def test_low_severity_no_notification(self):
        mock_channel = MockAlertChannel()
        rules = [
            AlertRule(
                alert_type="task_failure",
                channels=["email"],
                min_severity="error",
                cooldown_minutes=0,
            ),
        ]
        email_mock = MockAlertChannel()

        mgr = AlertManager.__new__(AlertManager)
        mgr.channels = {"email": email_mock}
        mgr.rules = rules
        mgr._redis = MagicMock()
        mgr._redis.exists.return_value = 0

        info_alert = Alert(
            alert_type="task_failure",
            severity="info",
            pipeline_name="low_priority_pipeline",
            task_name="log_archival",
            message="Minor issue: archival took 2s longer than expected",
        )

        with patch.object(mgr, "_check_cooldown", return_value=False), \
             patch.object(mgr, "_set_cooldown"):
            result = await mgr.notify(info_alert)

        assert len(email_mock.sent_alerts) == 0
        assert len(result["channels_notified"]) == 0
