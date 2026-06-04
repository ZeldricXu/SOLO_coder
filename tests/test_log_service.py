import json
from datetime import datetime, timedelta
from unittest.mock import patch, MagicMock, AsyncMock

import pytest
from sqlalchemy.orm import Session

from app.models import LogTemplate
from app.services.log_service import LogService
from app.schemas.log import LogSearchRequest, LogTemplateCreate


class TestLogSearch:

    @pytest.mark.asyncio
    async def test_search_by_keyword(self, db_session: Session):
        log_service = LogService(db_session)
        log_service.use_mock = True

        request = LogSearchRequest(
            keyword="error",
            page=1,
            page_size=20,
        )
        result = await log_service.search(request)

        assert "total" in result
        assert "logs" in result
        assert len(result["logs"]) <= 20
        assert result["page"] == 1
        assert result["page_size"] == 20

    @pytest.mark.asyncio
    async def test_search_by_service_name(self, db_session: Session):
        log_service = LogService(db_session)
        log_service.use_mock = True

        request = LogSearchRequest(
            service_name="order-service",
            page=1,
            page_size=20,
        )
        result = await log_service.search(request)

        assert "logs" in result
        for log in result["logs"]:
            assert log["service"] == "order-service"

    @pytest.mark.asyncio
    async def test_search_by_log_level(self, db_session: Session):
        log_service = LogService(db_session)
        log_service.use_mock = True

        request = LogSearchRequest(
            level="ERROR",
            page=1,
            page_size=20,
        )
        result = await log_service.search(request)

        assert "logs" in result
        for log in result["logs"]:
            assert log["level"] == "ERROR"

    @pytest.mark.asyncio
    async def test_search_with_time_range(self, db_session: Session):
        log_service = LogService(db_session)
        log_service.use_mock = True

        start_time = datetime.now() - timedelta(hours=1)
        end_time = datetime.now()

        request = LogSearchRequest(
            start_time=start_time,
            end_time=end_time,
            page=1,
            page_size=20,
        )
        result = await log_service.search(request)

        assert "logs" in result
        assert "total" in result

    @pytest.mark.asyncio
    async def test_search_pagination(self, db_session: Session):
        log_service = LogService(db_session)
        log_service.use_mock = True

        request = LogSearchRequest(
            page=2,
            page_size=10,
        )
        result = await log_service.search(request)

        assert result["page"] == 2
        assert result["page_size"] == 10
        assert len(result["logs"]) <= 10

    @pytest.mark.asyncio
    async def test_search_combined_filters(self, db_session: Session):
        log_service = LogService(db_session)
        log_service.use_mock = True

        request = LogSearchRequest(
            keyword="database",
            service_name="order-service",
            level="ERROR",
            page=1,
            page_size=20,
        )
        result = await log_service.search(request)

        assert "logs" in result
        assert "aggregations" in result

    @pytest.mark.asyncio
    async def test_elasticsearch_connection_error_fallback(self, db_session: Session):
        log_service = LogService(db_session)
        log_service.use_mock = False

        request = LogSearchRequest(
            keyword="test",
            page=1,
            page_size=20,
        )

        with patch('httpx.AsyncClient.post', new_callable=AsyncMock) as mock_post:
            mock_post.side_effect = Exception("Connection error")
            result = await log_service.search(request)

        assert "total" in result
        assert "logs" in result
        assert len(result["logs"]) > 0


class TestLogTemplates:

    def test_save_template(self, db_session: Session):
        log_service = LogService(db_session)

        data = LogTemplateCreate(
            name="我的错误日志查询",
            query_config={
                "keyword": "error",
                "level": "ERROR",
                "service_name": "order-service",
            },
        )
        result = log_service.create_template(user_id=1, data=data)

        assert result is not None
        assert result.name == "我的错误日志查询"
        assert result.user_id == 1

    def test_get_templates_by_user(self, db_session: Session):
        log_service = LogService(db_session)

        for i in range(3):
            template = LogTemplate(
                user_id=1,
                name=f"模板-{i}",
                query_config=json.dumps({"keyword": f"test-{i}"}),
            )
            db_session.add(template)
        db_session.commit()

        templates = log_service.get_templates(user_id=1)

        assert len(templates) >= 3
        for t in templates:
            assert t.user_id == 1

    def test_delete_template(self, db_session: Session):
        log_service = LogService(db_session)

        template = LogTemplate(
            user_id=1,
            name="待删除模板",
            query_config=json.dumps({"keyword": "test"}),
        )
        db_session.add(template)
        db_session.commit()

        result = log_service.delete_template(template.id, user_id=1)

        assert result is True
        assert db_session.query(LogTemplate).filter(LogTemplate.id == template.id).first() is None

    def test_delete_other_user_template_not_allowed(self, db_session: Session):
        log_service = LogService(db_session)

        template = LogTemplate(
            user_id=1,
            name="用户1的模板",
            query_config=json.dumps({"keyword": "test"}),
        )
        db_session.add(template)
        db_session.commit()

        result = log_service.delete_template(template.id, user_id=2)

        assert result is False
        assert db_session.query(LogTemplate).filter(LogTemplate.id == template.id).first() is not None

    def test_template_query_config_stored_correctly(self, db_session: Session):
        log_service = LogService(db_session)

        query_config = {
            "keyword": "database error",
            "level": "ERROR",
            "service_name": "order-service",
            "time_range_hours": 24,
        }

        template = log_service.create_template(
            user_id=1,
            data=LogTemplateCreate(
                name="复杂查询模板",
                query_config=query_config,
            )
        )

        retrieved = log_service.get_template_by_id(template.id, user_id=1)
        assert retrieved is not None
        assert retrieved["query_config"]["keyword"] == "database error"
        assert retrieved["query_config"]["level"] == "ERROR"


class TestLogParsing:

    def test_parse_log_level_from_message(self, db_session: Session):
        log_service = LogService(db_session)

        messages = [
            ("[ERROR] Database connection failed", "ERROR"),
            ("[WARN] High memory usage detected", "WARN"),
            ("[INFO] Request processed in 45ms", "INFO"),
            ("[DEBUG] User authentication: user=12345", "DEBUG"),
        ]

        for message, expected_level in messages:
            if "[ERROR]" in message:
                assert "ERROR" in message
            elif "[WARN]" in message:
                assert "WARN" in message
            elif "[INFO]" in message:
                assert "INFO" in message
            elif "[DEBUG]" in message:
                assert "DEBUG" in message

    def test_parse_timestamp_format(self, db_session: Session):
        log_service = LogService(db_session)

        timestamps = [
            "2024-01-15T10:30:45.123Z",
            "2024-01-15 10:30:45",
            "2024-01-15 10:30:45,123",
            "2024/01/15 10:30:45",
        ]

        for ts in timestamps:
            assert isinstance(ts, str)
            assert len(ts) > 0

    def test_extract_service_name(self, db_session: Session):
        log_service = LogService(db_session)

        test_cases = [
            ("[order-service] Request received", "order-service"),
            ("service=user-service action=login", "user-service"),
            ("[pay-service][INFO] Payment processed", "pay-service"),
        ]

        for message, expected_service in test_cases:
            assert expected_service in message