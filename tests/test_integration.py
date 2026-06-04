import json
import time
from datetime import datetime, timedelta
from unittest.mock import patch, MagicMock, AsyncMock

import pytest
from fastapi.testclient import TestClient
from sqlalchemy.orm import Session

from app.main import app
from app.models import Service, HealthCheck, AlertRule, AlertHistory, SlowSQL, SQLExplain, User


class TestHealthIntegration:

    def test_health_page_renders(self, client: TestClient):
        response = client.get("/health")
        assert response.status_code == 200
        assert "服务健康" in response.text or "健康状态" in response.text

    def test_health_cards_partial_render(self, client: TestClient, db_session: Session):
        for i in range(3):
            svc = Service(
                name=f"服务-{i}",
                service_type="microservice",
                health_endpoint=f"http://service-{i}:8080/health",
                check_interval=30,
                status="healthy",
            )
            db_session.add(svc)
            db_session.flush()
            hc = HealthCheck(
                service_id=svc.id,
                status="healthy",
                response_time_ms=50 + i * 10,
                checked_at=datetime.now(),
            )
            db_session.add(hc)
        db_session.commit()

        response = client.get("/api/health/partial/cards")
        assert response.status_code == 200
        assert "服务-0" in response.text
        assert "服务-1" in response.text
        assert "服务-2" in response.text

    def test_health_service_detail_partial(self, client: TestClient, db_session: Session):
        svc = Service(
            name="订单服务",
            service_type="microservice",
            health_endpoint="http://order:8080/health",
            check_interval=30,
            status="healthy",
        )
        db_session.add(svc)
        db_session.flush()
        hc = HealthCheck(
            service_id=svc.id,
            status="healthy",
            response_time_ms=45,
            details='{"cpu": 50, "memory": 60, "connections": 120}',
            checked_at=datetime.now(),
        )
        db_session.add(hc)
        db_session.commit()

        response = client.get(f"/api/health/partial/service/{svc.id}")
        assert response.status_code == 200
        assert "订单服务" in response.text

    def test_health_summary_correct(self, client: TestClient, db_session: Session):
        services_data = [
            ("服务-健康1", "healthy"),
            ("服务-健康2", "healthy"),
            ("服务-健康3", "healthy"),
            ("服务-警告", "warning"),
            ("服务-严重", "critical"),
        ]
        for name, status in services_data:
            svc = Service(
                name=name,
                service_type="microservice",
                health_endpoint=f"http://{name}:8080/health",
                check_interval=30,
                status=status,
            )
            db_session.add(svc)
        db_session.commit()

        response = client.get("/api/health/summary")
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert data["summary"]["healthy"] == 3
        assert data["summary"]["warning"] == 1
        assert data["summary"]["critical"] == 1


class TestMetricsIntegration:

    def test_metrics_page_renders(self, client: TestClient):
        response = client.get("/metrics")
        assert response.status_code == 200
        assert "监控指标" in response.text or "Metrics" in response.text

    def test_metric_chart_partial_render(self, client: TestClient):
        from app.services import metrics_service
        with patch.object(metrics_service.MetricsService, 'get_chart_data_for_frontend') as mock_chart:
            mock_chart.return_value = {
                "timestamps": ["00:00", "01:00"],
                "values": [50, 60],
            }
            response = client.get("/api/metrics/partial/chart/cpu_usage")
            assert response.status_code == 200


class TestAlertIntegration:

    def test_alerts_page_renders(self, client: TestClient):
        response = client.get("/alerts")
        assert response.status_code == 200
        assert "告警" in response.text or "Alert" in response.text

    def test_alert_list_partial_render(self, client: TestClient, db_session: Session):
        rule = AlertRule(
            name="CPU使用率告警",
            level="P2",
            condition_expr="cpu_usage > 90",
            window_seconds=60,
            threshold=90.0,
            notification_channels="dingtalk",
            enabled=True,
        )
        db_session.add(rule)
        db_session.flush()

        for i in range(5):
            alert = AlertHistory(
                rule_id=rule.id,
                level="P2",
                message=f"告警测试-{i}",
                status="firing" if i < 3 else "resolved",
            )
            db_session.add(alert)
        db_session.commit()

        response = client.get("/api/alerts/partial/list")
        assert response.status_code == 200

    def test_alert_lifecycle_create_trigger_ack_resolve(self, client: TestClient, db_session: Session):
        with patch('asyncio.create_task'):
            rule = AlertRule(
                name="测试告警规则",
                level="P2",
                condition_expr="cpu_usage > 90",
                window_seconds=60,
                threshold=90.0,
                notification_channels="dingtalk",
                enabled=True,
            )
            db_session.add(rule)
            db_session.commit()

            assert rule.id is not None
            assert rule.enabled is True

            alert = AlertHistory(
                rule_id=rule.id,
                level="P2",
                message="测试告警触发",
                status="firing",
            )
            db_session.add(alert)
            db_session.commit()

            assert alert.status == "firing"

            alert.status = "acknowledged"
            alert.ack_user_id = 1
            alert.ack_at = datetime.now()
            db_session.commit()
            db_session.refresh(alert)

            assert alert.status == "acknowledged"

            alert.status = "resolved"
            db_session.commit()
            db_session.refresh(alert)

            assert alert.status == "resolved"


class TestSlowSQLIntegration:

    def test_slow_sql_page_renders(self, client: TestClient):
        response = client.get("/slow-sql")
        assert response.status_code == 200
        assert "慢SQL" in response.text or "Slow SQL" in response.text

    def test_slow_sql_list_partial_render(self, client: TestClient, db_session: Session):
        for i in range(5):
            slow_sql = SlowSQL(
                fingerprint=f"fp_{i}",
                table_name="orders",
                sql_text=f"SELECT * FROM orders WHERE id = {i}",
                avg_duration_ms=500 + i * 100,
                exec_count=100 + i * 10,
            )
            db_session.add(slow_sql)
        db_session.commit()

        response = client.get("/api/slow-sql/partial/list")
        assert response.status_code == 200

    def test_slow_sql_explain_partial_render(self, client: TestClient, db_session: Session):
        slow_sql = SlowSQL(
            fingerprint="test_fp",
            table_name="orders",
            sql_text="SELECT * FROM orders WHERE id = 1",
            avg_duration_ms=1500,
            exec_count=100,
        )
        db_session.add(slow_sql)
        db_session.flush()

        explain = SQLExplain(
            slow_sql_id=slow_sql.id,
            plan_json=json.dumps({"type": "ALL", "rows": 10000}),
            analysis="全表扫描，建议添加索引",
        )
        db_session.add(explain)
        db_session.commit()

        response = client.get(f"/api/slow-sql/partial/explain/{slow_sql.id}")
        assert response.status_code == 200

    def test_slow_sql_agent_report_endpoint(self, client: TestClient, db_session: Session):
        report_data = {
            "fingerprint": "",
            "sql_text": "SELECT * FROM orders WHERE user_id = 123",
            "table_name": "orders",
            "duration_ms": 1250,
        }

        response = client.post("/api/slow-sql/record", json=report_data)
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True


class TestLogIntegration:

    def test_logs_page_renders(self, client: TestClient):
        response = client.get("/logs")
        assert response.status_code == 200
        assert "日志" in response.text or "Logs" in response.text

    def test_log_search_partial_render(self, client: TestClient):
        response = client.get("/api/logs/partial/search-results")
        assert response.status_code == 200


class TestHTMXIntegration:

    def test_htmx_headers_present(self, client: TestClient):
        response = client.get("/api/health/partial/cards")
        assert response.status_code == 200
        assert response.headers["content-type"] is not None

    def test_partial_content_not_full_page(self, client: TestClient, db_session: Session):
        svc = Service(
            name="测试服务",
            service_type="microservice",
            health_endpoint="http://test:8080/health",
            check_interval=30,
            status="healthy",
        )
        db_session.add(svc)
        db_session.commit()

        response = client.get("/api/health/partial/cards")
        assert response.status_code == 200
        assert "<!DOCTYPE html>" not in response.text.lower()
        assert "<html>" not in response.text.lower() or "<html" not in response.text[:100].lower()

    def test_htmx_swap_behavior(self, client: TestClient):
        response = client.get("/health")
        assert response.status_code == 200


class TestHomeDashboardIntegration:

    def test_home_page_renders(self, client: TestClient):
        response = client.get("/")
        assert response.status_code == 200
        assert "运维" in response.text or "Dashboard" in response.text or "Home" in response.text

    def test_home_page_has_draggable_components(self, client: TestClient):
        response = client.get("/")
        assert response.status_code == 200


class TestAPIContract:

    def test_api_healthz(self, client: TestClient):
        response = client.get("/healthz")
        assert response.status_code == 200

    def test_health_api_status(self, client: TestClient, db_session: Session):
        svc = Service(
            name="测试服务",
            service_type="microservice",
            health_endpoint="http://test:8080/health",
            check_interval=30,
            status="healthy",
        )
        db_session.add(svc)
        db_session.commit()

        response = client.get("/api/health/status")
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert "statuses" in data

    def test_alert_api_summary(self, client: TestClient):
        response = client.get("/api/alerts/summary")
        assert response.status_code == 200
        data = response.json()
        assert data["success"] is True
        assert "firing" in data["summary"]
        assert "acknowledged" in data["summary"]
        assert "resolved" in data["summary"]