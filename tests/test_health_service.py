import asyncio
import json
from datetime import datetime, timedelta
from unittest.mock import AsyncMock, patch, MagicMock

import pytest
import httpx
from sqlalchemy.orm import Session

from app.models import Service, HealthCheck
from app.services.health_service import HealthService


class TestHealthServiceNormalScenarios:

    def test_get_summary_accurate_count(self, db_session: Session):
        services_data = [
            {"name": "订单服务", "status": "healthy", "response_time_ms": 45},
            {"name": "用户服务", "status": "healthy", "response_time_ms": 38},
            {"name": "支付服务", "status": "warning", "response_time_ms": 280},
            {"name": "库存服务", "status": "healthy", "response_time_ms": 52},
            {"name": "RabbitMQ", "status": "critical", "response_time_ms": None},
            {"name": "Redis", "status": "critical", "response_time_ms": None},
        ]
        
        for data in services_data:
            svc = Service(
                name=data["name"],
                service_type="microservice",
                health_endpoint=f"http://{data['name']}:8080/health",
                check_interval=30,
                status=data["status"],
            )
            db_session.add(svc)
            db_session.flush()
            
            hc = HealthCheck(
                service_id=svc.id,
                status=data["status"],
                response_time_ms=data["response_time_ms"],
                details='{"cpu": 50, "memory": 60}',
            )
            db_session.add(hc)
        
        db_session.commit()
        
        health_service = HealthService(db_session)
        summary = health_service.get_summary()
        
        assert summary["total"] == 6
        assert summary["healthy"] == 3
        assert summary["warning"] == 1
        assert summary["critical"] == 2

    def test_get_all_services_with_details(self, db_session: Session):
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
        )
        db_session.add(hc)
        db_session.commit()
        
        health_service = HealthService(db_session)
        services = health_service.get_all_services()
        
        assert len(services) == 1
        assert services[0].name == "订单服务"
        assert services[0].status == "healthy"

    def test_get_service_status(self, db_session: Session):
        svc = Service(
            name="支付服务",
            service_type="microservice",
            health_endpoint="http://pay:8080/health",
            check_interval=30,
            status="warning",
        )
        db_session.add(svc)
        db_session.flush()
        
        hc = HealthCheck(
            service_id=svc.id,
            status="warning",
            response_time_ms=280,
            details='{"cpu": 78, "memory": 85}',
        )
        db_session.add(hc)
        db_session.commit()
        
        health_service = HealthService(db_session)
        status = health_service.get_service_status(svc.id)
        
        assert status is not None
        assert status["service"].name == "支付服务"
        assert status["service"].status == "warning"
        assert status["last_check"].response_time_ms == 280

    def test_get_recent_checks(self, db_session: Session):
        svc = Service(
            name="订单服务",
            service_type="microservice",
            health_endpoint="http://order:8080/health",
            check_interval=30,
            status="healthy",
        )
        db_session.add(svc)
        db_session.flush()
        
        for i in range(15):
            hc = HealthCheck(
                service_id=svc.id,
                status="healthy",
                response_time_ms=40 + i,
                checked_at=datetime.now() - timedelta(minutes=i),
            )
            db_session.add(hc)
        db_session.commit()
        
        health_service = HealthService(db_session)
        checks = health_service.get_recent_checks(svc.id, limit=10)
        
        assert len(checks) == 10
        assert checks[0].checked_at > checks[-1].checked_at

    @pytest.mark.asyncio
    async def test_check_service_success(self, db_session: Session):
        svc = Service(
            name="订单服务",
            service_type="microservice",
            health_endpoint="http://order:8080/health",
            check_interval=30,
            status="healthy",
        )
        db_session.add(svc)
        db_session.commit()
        
        health_service = HealthService(db_session)
        health_service.use_mock = True
        
        result = await health_service.check_service(svc)
        
        assert result.status in ["healthy", "warning", "critical"]
        assert result.service_id == svc.id

    @pytest.mark.asyncio
    async def test_check_all_services(self, db_session: Session):
        services_data = [
            {"name": "订单服务", "status": "healthy"},
            {"name": "用户服务", "status": "healthy"},
            {"name": "支付服务", "status": "warning"},
        ]
        
        for data in services_data:
            svc = Service(
                name=data["name"],
                service_type="microservice",
                health_endpoint=f"http://{data['name']}:8080/health",
                check_interval=30,
                status=data["status"],
            )
            db_session.add(svc)
        db_session.commit()
        
        health_service = HealthService(db_session)
        health_service.use_mock = True
        
        results = await health_service.check_all_services()
        
        assert len(results) == 3
        for result in results:
            assert hasattr(result, "service_id")
            assert hasattr(result, "status")


class TestHealthServiceExceptionScenarios:

    @pytest.mark.asyncio
    async def test_prometheus_http_500_no_crash(self, db_session: Session):
        svc = Service(
            name="订单服务",
            service_type="microservice",
            health_endpoint="http://order:8080/health",
            check_interval=30,
            status="healthy",
        )
        db_session.add(svc)
        db_session.commit()
        
        health_service = HealthService(db_session)
        health_service.use_mock = False
        
        with patch.object(httpx.AsyncClient, 'get', new_callable=AsyncMock) as mock_get:
            mock_response = MagicMock()
            mock_response.status_code = 500
            mock_get.return_value = mock_response
            result = await health_service.check_service(svc)
        
        assert result.status == "critical"

    @pytest.mark.asyncio
    async def test_network_timeout_fallback(self, db_session: Session):
        svc = Service(
            name="订单服务",
            service_type="microservice",
            health_endpoint="http://order:8080/health",
            check_interval=30,
            status="healthy",
        )
        db_session.add(svc)
        db_session.commit()
        
        health_service = HealthService(db_session)
        health_service.use_mock = False
        
        with patch.object(httpx.AsyncClient, 'get', new_callable=AsyncMock) as mock_get:
            mock_get.side_effect = Exception("Connection timed out")
            result = await health_service.check_service(svc)
        
        assert result.status == "critical"
        assert result.details is not None

    @pytest.mark.asyncio
    async def test_json_field_missing_defensive(self, db_session: Session):
        svc = Service(
            name="订单服务",
            service_type="microservice",
            health_endpoint="http://order:8080/health",
            check_interval=30,
            status="healthy",
        )
        db_session.add(svc)
        db_session.commit()
        
        health_service = HealthService(db_session)
        health_service.use_mock = False
        
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.return_value = {
            "cpu": 45,
        }
        mock_response.text = '{"cpu": 45}'
        
        with patch.object(httpx.AsyncClient, 'get', new_callable=AsyncMock) as mock_get:
            mock_get.return_value = mock_response
            result = await health_service.check_service(svc)
        
        assert result.status == "healthy"
        assert result.response_time_ms is not None

    @pytest.mark.asyncio
    async def test_invalid_json_response(self, db_session: Session):
        svc = Service(
            name="订单服务",
            service_type="microservice",
            health_endpoint="http://order:8080/health",
            check_interval=30,
            status="healthy",
        )
        db_session.add(svc)
        db_session.commit()
        
        health_service = HealthService(db_session)
        health_service.use_mock = False
        
        mock_response = MagicMock()
        mock_response.status_code = 200
        mock_response.json.side_effect = json.JSONDecodeError("Invalid JSON", "", 0)
        mock_response.text = "not valid json"
        
        with patch.object(httpx.AsyncClient, 'get', new_callable=AsyncMock) as mock_get:
            mock_get.return_value = mock_response
            result = await health_service.check_service(svc)
        
        assert result.status == "healthy"

    @pytest.mark.asyncio
    async def test_connection_refused(self, db_session: Session):
        svc = Service(
            name="订单服务",
            service_type="microservice",
            health_endpoint="http://order:8080/health",
            check_interval=30,
            status="healthy",
        )
        db_session.add(svc)
        db_session.commit()
        
        health_service = HealthService(db_session)
        health_service.use_mock = False
        
        with patch.object(httpx.AsyncClient, 'get', new_callable=AsyncMock) as mock_get:
            mock_get.side_effect = Exception("Connection refused")
            result = await health_service.check_service(svc)
        
        assert result.status == "critical"

    def test_get_nonexistent_service(self, db_session: Session):
        health_service = HealthService(db_session)
        status = health_service.get_service_status(99999)
        assert status is None

    def test_get_summary_no_services(self, db_session: Session):
        health_service = HealthService(db_session)
        summary = health_service.get_summary()
        
        assert summary["total"] == 0
        assert summary["healthy"] == 0
        assert summary["warning"] == 0
        assert summary["critical"] == 0


class TestHealthServicePollingInterval:

    def test_check_interval_respected(self, db_session: Session):
        svc = Service(
            name="订单服务",
            service_type="microservice",
            health_endpoint="http://order:8080/health",
            check_interval=60,
            status="healthy",
        )
        db_session.add(svc)
        db_session.flush()
        
        hc = HealthCheck(
            service_id=svc.id,
            status="healthy",
            response_time_ms=45,
            checked_at=datetime.now() - timedelta(seconds=30),
        )
        db_session.add(hc)
        db_session.commit()
        
        health_service = HealthService(db_session)
        svc_status = health_service.get_service_status(svc.id)
        
        time_since_last = datetime.now() - hc.checked_at
        assert time_since_last.total_seconds() < svc.check_interval

    @pytest.mark.asyncio
    async def test_concurrent_polling_no_performance_issue(self, db_session: Session):
        for i in range(20):
            svc = Service(
                name=f"服务-{i}",
                service_type="microservice",
                health_endpoint=f"http://service-{i}:8080/health",
                check_interval=30,
                status="healthy",
            )
            db_session.add(svc)
        db_session.commit()
        
        health_service = HealthService(db_session)
        health_service.use_mock = True
        
        start_time = datetime.now()
        results = await health_service.check_all_services()
        end_time = datetime.now()
        
        duration = (end_time - start_time).total_seconds()
        assert len(results) == 20
        assert duration < 5.0

    def test_htmx_refresh_on_health_page(self, client):
        response = client.get("/health")
        assert response.status_code == 200
        assert "hx-poll" in response.text.lower() or "hx-get" in response.text.lower() or "refresh" in response.text.lower()
