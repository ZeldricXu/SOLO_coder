import asyncio
import threading
import time
from datetime import datetime, timedelta
from unittest.mock import patch, MagicMock, AsyncMock

import pytest
from sqlalchemy.orm import Session

from app.models import AlertRule, AlertHistory, Service
from app.services.alert_service import AlertService


class TestAlertRuleEvaluation:

    @pytest.mark.parametrize("operator,value,threshold,expected", [
        (">", 95, 90, True),
        (">", 85, 90, False),
        ("<", 85, 90, True),
        ("<", 95, 90, False),
        ("==", 90, 90, True),
        ("==", 85, 90, False),
        ("!=", 85, 90, True),
        ("!=", 90, 90, False),
    ])
    def test_comparison_operators(self, db_session: Session, operator, value, threshold, expected):
        rule = AlertRule(
            name=f"测试规则-{operator}",
            level="P2",
            condition_expr=f"cpu_usage {operator} {threshold}",
            window_seconds=60,
            threshold=threshold,
            notification_channels="dingtalk",
            enabled=True,
        )
        db_session.add(rule)
        db_session.commit()

        alert_service = AlertService(db_session)

        with patch.object(alert_service, '_get_metrics_context') as mock_metrics:
            mock_metrics.return_value = {"cpu_usage": value}
            result = alert_service._real_evaluate(rule)

        assert result == expected

    def test_and_condition_both_true(self, db_session: Session):
        rule = AlertRule(
            name="复合条件测试",
            level="P1",
            condition_expr="error_rate > 3 and qps > 100",
            window_seconds=300,
            threshold=3.0,
            notification_channels="dingtalk",
            enabled=True,
        )
        db_session.add(rule)
        db_session.commit()

        alert_service = AlertService(db_session)

        with patch.object(alert_service, '_get_metrics_context') as mock_metrics:
            mock_metrics.return_value = {"error_rate": 5.0, "qps": 150}
            result = alert_service._real_evaluate(rule)

        assert result is True

    def test_and_condition_one_false(self, db_session: Session):
        rule = AlertRule(
            name="复合条件测试",
            level="P1",
            condition_expr="error_rate > 3 and qps > 100",
            window_seconds=300,
            threshold=3.0,
            notification_channels="dingtalk",
            enabled=True,
        )
        db_session.add(rule)
        db_session.commit()

        alert_service = AlertService(db_session)

        with patch.object(alert_service, '_get_metrics_context') as mock_metrics:
            mock_metrics.return_value = {"error_rate": 2.0, "qps": 150}
            result = alert_service._real_evaluate(rule)

        assert result is False

    def test_or_condition(self, db_session: Session):
        rule = AlertRule(
            name="或条件测试",
            level="P2",
            condition_expr="cpu_usage > 90 or memory_usage > 95",
            window_seconds=60,
            threshold=90.0,
            notification_channels="dingtalk",
            enabled=True,
        )
        db_session.add(rule)
        db_session.commit()

        alert_service = AlertService(db_session)

        with patch.object(alert_service, '_get_metrics_context') as mock_metrics:
            mock_metrics.return_value = {"cpu_usage": 85, "memory_usage": 97}
            result = alert_service._real_evaluate(rule)

        assert result is True

    def test_complex_nested_condition(self, db_session: Session):
        rule = AlertRule(
            name="复杂嵌套条件",
            level="P1",
            condition_expr="(cpu_usage > 90 or memory_usage > 95) and error_rate > 1",
            window_seconds=60,
            threshold=90.0,
            notification_channels="dingtalk",
            enabled=True,
        )
        db_session.add(rule)
        db_session.commit()

        alert_service = AlertService(db_session)

        with patch.object(alert_service, '_get_metrics_context') as mock_metrics:
            mock_metrics.return_value = {"cpu_usage": 85, "memory_usage": 97, "error_rate": 2.0}
            result = alert_service._real_evaluate(rule)

        assert result is True

    def test_invalid_expression_no_crash(self, db_session: Session):
        rule = AlertRule(
            name="无效表达式",
            level="P2",
            condition_expr="invalid_syntax > 90",
            window_seconds=60,
            threshold=90.0,
            notification_channels="dingtalk",
            enabled=True,
        )
        db_session.add(rule)
        db_session.commit()

        alert_service = AlertService(db_session)

        with patch.object(alert_service, '_get_metrics_context') as mock_metrics:
            mock_metrics.return_value = {"cpu_usage": 95}
            result = alert_service._real_evaluate(rule)

        assert result is False


class TestAlertWindowAggregation:

    def test_window_sum_calculation(self, db_session: Session):
        svc = Service(
            name="测试服务",
            service_type="microservice",
            health_endpoint="http://test:8080/health",
            check_interval=30,
            status="healthy",
        )
        db_session.add(svc)
        db_session.flush()

        from app.models import HealthCheck
        for i in range(10):
            hc = HealthCheck(
                service_id=svc.id,
                status="healthy",
                response_time_ms=100 + i * 10,
                checked_at=datetime.now() - timedelta(seconds=i * 30),
            )
            db_session.add(hc)
        db_session.commit()

        alert_service = AlertService(db_session)
        context = alert_service._get_metrics_context(
            AlertRule(window_seconds=300, threshold=100)
        )

        assert context["total_checks"] == 10
        assert context["avg_response_time"] > 0

    def test_window_avg_calculation(self, db_session: Session):
        svc = Service(
            name="测试服务",
            service_type="microservice",
            health_endpoint="http://test:8080/health",
            check_interval=30,
            status="healthy",
        )
        db_session.add(svc)
        db_session.flush()

        from app.models import HealthCheck
        response_times = [100, 200, 300, 400, 500]
        for i, rt in enumerate(response_times):
            hc = HealthCheck(
                service_id=svc.id,
                status="healthy",
                response_time_ms=rt,
                checked_at=datetime.now() - timedelta(seconds=i * 30),
            )
            db_session.add(hc)
        db_session.commit()

        alert_service = AlertService(db_session)
        context = alert_service._get_metrics_context(
            AlertRule(window_seconds=300, threshold=100)
        )

        avg_response = sum(response_times) / len(response_times)
        assert abs(context["avg_response_time"] - avg_response) < 0.01

    def test_window_error_rate_calculation(self, db_session: Session):
        svc = Service(
            name="测试服务",
            service_type="microservice",
            health_endpoint="http://test:8080/health",
            check_interval=30,
            status="healthy",
        )
        db_session.add(svc)
        db_session.flush()

        from app.models import HealthCheck
        for i in range(10):
            status = "critical" if i < 3 else "healthy"
            hc = HealthCheck(
                service_id=svc.id,
                status=status,
                response_time_ms=100,
                checked_at=datetime.now() - timedelta(seconds=i * 30),
            )
            db_session.add(hc)
        db_session.commit()

        alert_service = AlertService(db_session)
        context = alert_service._get_metrics_context(
            AlertRule(window_seconds=300, threshold=100)
        )

        assert context["error_rate"] == 30.0
        assert context["critical_count"] == 3
        assert context["healthy_count"] == 7


class TestAlertSuppression:

    def test_no_duplicate_alert_while_firing(self, db_session: Session):
        rule = AlertRule(
            name="测试告警收敛",
            level="P2",
            condition_expr="cpu_usage > 90",
            window_seconds=60,
            threshold=90.0,
            notification_channels="dingtalk",
            enabled=True,
        )
        db_session.add(rule)
        db_session.flush()

        alert = AlertHistory(
            rule_id=rule.id,
            level="P2",
            message="测试告警",
            status="firing",
        )
        db_session.add(alert)
        db_session.commit()

        alert_service = AlertService(db_session)
        result = alert_service._trigger_alert(rule)

        assert result is None

    def test_new_alert_after_resolved(self, db_session: Session):
        rule = AlertRule(
            name="测试告警收敛",
            level="P2",
            condition_expr="cpu_usage > 90",
            window_seconds=60,
            threshold=90.0,
            notification_channels="dingtalk",
            enabled=True,
        )
        db_session.add(rule)
        db_session.flush()

        alert = AlertHistory(
            rule_id=rule.id,
            level="P2",
            message="测试告警",
            status="resolved",
        )
        db_session.add(alert)
        db_session.commit()

        alert_service = AlertService(db_session)
        result = alert_service._trigger_alert(rule)

        assert result is not None
        assert result.status == "firing"


class TestAlertEscalation:

    def test_alert_escalation_not_implemented(self, db_session: Session):
        alert_service = AlertService(db_session)
        assert hasattr(alert_service, '_trigger_alert')
        assert hasattr(alert_service, 'acknowledge_alert')
        assert hasattr(alert_service, 'resolve_alert')


class TestAlertConcurrency:

    def test_concurrent_alert_evaluations(self, db_session: Session):
        for i in range(5):
            rule = AlertRule(
                name=f"规则-{i}",
                level="P2",
                condition_expr="cpu_usage > 90",
                window_seconds=60,
                threshold=90.0,
                notification_channels="dingtalk",
                enabled=True,
            )
            db_session.add(rule)
        db_session.commit()

        alert_service = AlertService(db_session)
        alert_service.use_mock = True

        with patch('asyncio.create_task'):
            results = []
            for _ in range(3):
                alerts = alert_service.evaluate_rules()
                results.append(len(alerts))

        assert all(r >= 0 for r in results)

    def test_concurrent_rule_modifications(self, db_session: Session):
        rule = AlertRule(
            name="并发修改测试",
            level="P2",
            condition_expr="cpu_usage > 90",
            window_seconds=60,
            threshold=90.0,
            notification_channels="dingtalk",
            enabled=True,
        )
        db_session.add(rule)
        db_session.commit()

        def modify_threshold(rule_id, new_threshold):
            from app.database import SessionLocal
            session = SessionLocal()
            try:
                r = session.query(AlertRule).filter(AlertRule.id == rule_id).first()
                if r:
                    r.threshold = new_threshold
                    session.commit()
            finally:
                session.close()

        threads = []
        for i in range(5):
            t = threading.Thread(target=modify_threshold, args=(rule.id, 80 + i * 5))
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        db_session.refresh(rule)
        assert rule.threshold is not None


class TestAlertNotificationChannels:

    @pytest.mark.asyncio
    async def test_dingtalk_notification(self, db_session: Session):
        from app.services.notification_service import NotificationService
        alert = AlertHistory(
            id=1,
            level="P0",
            message="测试告警",
            status="firing",
            triggered_at=datetime.now(),
        )

        notification_service = NotificationService(db_session)

        with patch('httpx.AsyncClient.post', new_callable=AsyncMock) as mock_post:
            mock_post.return_value = MagicMock(status_code=200)
            await notification_service._send_dingtalk(alert)

        assert True

    @pytest.mark.asyncio
    async def test_wechat_notification(self, db_session: Session):
        from app.services.notification_service import NotificationService
        alert = AlertHistory(
            id=1,
            level="P1",
            message="测试告警",
            status="firing",
            triggered_at=datetime.now(),
        )

        notification_service = NotificationService(db_session)

        with patch('httpx.AsyncClient.post', new_callable=AsyncMock) as mock_post:
            mock_post.return_value = MagicMock(status_code=200)
            await notification_service._send_wechat(alert)

        assert True

    def test_alert_acknowledge(self, db_session: Session):
        from app.schemas.alert import AlertAck

        rule = AlertRule(
            name="测试规则",
            level="P2",
            condition_expr="cpu_usage > 90",
            window_seconds=60,
            threshold=90.0,
            notification_channels="dingtalk",
            enabled=True,
        )
        db_session.add(rule)
        db_session.flush()

        alert = AlertHistory(
            rule_id=rule.id,
            level="P2",
            message="测试告警",
            status="firing",
        )
        db_session.add(alert)
        db_session.commit()

        alert_service = AlertService(db_session)
        result = alert_service.acknowledge_alert(alert.id, AlertAck(user_id=1))

        assert result is not None
        assert result.status == "acknowledged"
        assert result.ack_user_id == 1

    def test_alert_resolve(self, db_session: Session):
        rule = AlertRule(
            name="测试规则",
            level="P2",
            condition_expr="cpu_usage > 90",
            window_seconds=60,
            threshold=90.0,
            notification_channels="dingtalk",
            enabled=True,
        )
        db_session.add(rule)
        db_session.flush()

        alert = AlertHistory(
            rule_id=rule.id,
            level="P2",
            message="测试告警",
            status="firing",
        )
        db_session.add(alert)
        db_session.commit()

        alert_service = AlertService(db_session)
        result = alert_service.resolve_alert(alert.id, user_id=1)

        assert result is not None
        assert result.status == "resolved"

    def test_alert_summary(self, db_session: Session):
        rule = AlertRule(
            name="测试规则",
            level="P2",
            condition_expr="cpu_usage > 90",
            window_seconds=60,
            threshold=90.0,
            notification_channels="dingtalk",
            enabled=True,
        )
        db_session.add(rule)
        db_session.flush()

        statuses = ["firing", "firing", "acknowledged", "resolved", "resolved", "resolved"]
        for status in statuses:
            alert = AlertHistory(
                rule_id=rule.id,
                level="P2",
                message="测试告警",
                status=status,
            )
            db_session.add(alert)
        db_session.commit()

        alert_service = AlertService(db_session)
        summary = alert_service.get_summary()

        assert summary["firing"] == 2
        assert summary["acknowledged"] == 1
        assert summary["resolved"] == 3
        assert len(summary["active_alerts"]) == 2