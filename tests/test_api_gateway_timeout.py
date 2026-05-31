import pytest
import asyncio
import time
import json
from unittest.mock import patch, MagicMock, AsyncMock
from typing import Dict, Any, Optional
from starlette.requests import Request
from starlette.responses import Response, JSONResponse
from starlette.testclient import TestClient

from modules.api_gateway.middleware import (
    RequestTracingMiddleware,
    RateLimiterMiddleware,
    CORSMiddleware,
)
from modules.api_gateway.tracing import (
    Span,
    generate_trace_id,
    generate_span_id,
    get_current_trace_id,
    get_current_span_id,
    start_span,
)
from tests.builders import RequestBuilder, SpanBuilder, TraceContextBuilder


class TestTracingCore:
    def test_generate_trace_id_format(self):
        trace_id = generate_trace_id()
        assert isinstance(trace_id, str)
        assert len(trace_id) > 0

    def test_generate_span_id_format(self):
        span_id = generate_span_id()
        assert isinstance(span_id, str)
        assert len(span_id) > 0

    def test_trace_id_uniqueness(self):
        ids = {generate_trace_id() for _ in range(100)}
        assert len(ids) == 100

    def test_span_id_uniqueness(self):
        ids = {generate_span_id() for _ in range(100)}
        assert len(ids) == 100


class TestSpanContext:
    def test_span_basic_properties(self):
        trace_ctx = TraceContextBuilder().build()
        span = Span(
            name="test-span",
            service_name="test-service",
            trace_id=trace_ctx["trace_id"],
            span_id=trace_ctx["span_id"],
        )

        assert span.name == "test-span"
        assert span.service_name == "test-service"
        assert span.trace_id == trace_ctx["trace_id"]
        assert span.span_id == trace_ctx["span_id"]
        assert span.status is None
        assert span.started_at is not None

    def test_span_with_exception(self):
        trace_ctx = TraceContextBuilder().build()
        span = Span(
            name="error-span",
            service_name="test-service",
            trace_id=trace_ctx["trace_id"],
            span_id=trace_ctx["span_id"],
        )

        with patch.object(span, "_save_async"):
            try:
                with span:
                    raise ValueError("Test error")
            except ValueError:
                pass

            assert span.status == "error"
            assert "Test error" in span.status_message
            assert span.ended_at is not None

    def test_span_duration_calculation(self):
        from datetime import datetime, timezone, timedelta

        span = Span(name="duration-test", service_name="test")
        span.started_at = datetime.now(timezone.utc) - timedelta(milliseconds=500)
        span.ended_at = datetime.now(timezone.utc)

        duration = span.duration_ms
        assert duration is not None
        assert duration > 400

    def test_set_attribute(self):
        span = Span(name="attr-test", service_name="test")
        span.set_attribute("key1", "value1")
        span.set_attribute("key2", 123)

        assert span.attributes["key1"] == "value1"
        assert span.attributes["key2"] == 123

    def test_set_status(self):
        span = Span(name="status-test", service_name="test")
        span.set_status("ok", "All good")

        assert span.status == "ok"
        assert span.status_message == "All good"

    def test_start_span_with_parent(self):
        parent = Span(name="parent", service_name="test")
        child = start_span(
            name="child",
            service_name="test",
            parent_span=parent,
        )

        assert child.trace_id == parent.trace_id
        assert child.parent_span_id == parent.span_id


class TestRateLimiterMiddleware:
    @pytest.fixture
    def ok_app(self):
        async def app(scope, receive, send):
            response = JSONResponse({"code": 200, "data": {}})
            await response(scope, receive, send)
        return app

    def test_rate_limit_not_exceeded(self, ok_app):
        middleware = RateLimiterMiddleware(ok_app, requests_per_minute=5)
        client = TestClient(middleware)

        for i in range(3):
            response = client.get("/test")
            assert response.status_code == 200

    def test_rate_limit_per_client(self, ok_app):
        middleware = RateLimiterMiddleware(ok_app, requests_per_minute=2)
        client = TestClient(middleware)

        response = client.get("/test", headers={"X-Forwarded-For": "192.168.1.1"})
        assert response.status_code == 200

        response = client.get("/test", headers={"X-Forwarded-For": "192.168.1.2"})
        assert response.status_code == 200


class TestCORSMiddleware:
    def test_preflight_request(self):
        async def app(scope, receive, send):
            pass

        middleware = CORSMiddleware(app)
        client = TestClient(middleware)

        response = client.options("/test")
        assert response.status_code == 204
        assert response.headers["Access-Control-Allow-Origin"] == "*"
        assert "GET" in response.headers["Access-Control-Allow-Methods"]
        assert "POST" in response.headers["Access-Control-Allow-Methods"]

    def test_cors_headers_on_get(self):
        async def app(scope, receive, send):
            response = JSONResponse({"code": 200, "data": {}})
            await response(scope, receive, send)

        middleware = CORSMiddleware(app)
        client = TestClient(middleware)
        response = client.get("/test")
        assert response.headers["Access-Control-Allow-Origin"] == "*"


class TestTimeoutHandling:
    @pytest.fixture
    def fast_app(self):
        async def app(scope, receive, send):
            response = JSONResponse({"code": 200, "data": {"fast": True}})
            await response(scope, receive, send)
        return app

    @pytest.mark.asyncio
    async def test_request_duration_tracking(self, fast_app):
        middleware = RequestTracingMiddleware(fast_app, service_name="test")

        scope = {
            "type": "http",
            "method": "GET",
            "path": "/fast",
            "headers": [],
            "query_string": b"",
            "client": ("127.0.0.1", 12345),
        }

        async def receive():
            return {"type": "http.request", "body": b""}

        response_headers = []
        async def send(message):
            if message["type"] == "http.response.start":
                response_headers.extend(message["headers"])

        with patch("modules.api_gateway.tracing.log_request", new_callable=AsyncMock):
            with patch("modules.api_gateway.tracing.update_request_log", new_callable=AsyncMock):
                await middleware(scope, receive, send)

        header_names = [h[0].decode().lower() for h in response_headers]
        assert "x-trace-id" in header_names
        assert "x-span-id" in header_names


class TestDegradedBehavior:
    def test_fallback_when_tracing_fails(self):
        with patch("modules.api_gateway.tracing.log_request", side_effect=Exception("DB down")):
            trace_ctx = TraceContextBuilder().build()
            span = Span(
                name="degraded-span",
                service_name="test",
                trace_id=trace_ctx["trace_id"],
                span_id=trace_ctx["span_id"],
            )

            with patch.object(span, "_save_async"):
                with span:
                    span.set_attribute("degraded", True)

                assert span.status == "ok"
                assert span.ended_at is not None

    def test_fallback_when_log_update_fails(self):
        with patch("modules.api_gateway.tracing.update_request_log", side_effect=Exception("Update failed")):
            trace_ctx = TraceContextBuilder().build()
            span = Span(
                name="log-fallback-span",
                service_name="test",
                trace_id=trace_ctx["trace_id"],
                span_id=trace_ctx["span_id"],
            )

            with patch.object(span, "_save_async"):
                with span:
                    pass

                assert span.status == "ok"


class TestBoundaryValidation:
    def test_span_with_empty_name(self):
        span = Span(name="", service_name="test")
        assert span.name == "unknown"

    def test_span_with_none_name(self):
        span = Span(name=None, service_name="test")
        assert span.name == "unknown"

    def test_span_with_very_long_name(self):
        long_name = "a" * 1000
        span = Span(name=long_name, service_name="test")
        assert len(span.name) == 256

    def test_span_with_empty_service_name(self):
        span = Span(name="test", service_name="")
        assert span.service_name == "unknown"

    def test_span_with_none_service_name(self):
        span = Span(name="test", service_name=None)
        assert span.service_name == "unknown"

    def test_trace_id_validation_empty_string(self):
        span = Span(name="test", service_name="test", trace_id="")
        assert span.trace_id != ""
        assert len(span.trace_id) > 0

    def test_trace_id_validation_none(self):
        span = Span(name="test", service_name="test", trace_id=None)
        assert span.trace_id is not None

    def test_trace_id_validation_too_long(self):
        long_id = "a" * 200
        span = Span(name="test", service_name="test", trace_id=long_id)
        assert span.trace_id != long_id
        assert len(span.trace_id) == 36

    def test_span_id_validation_empty_string(self):
        span = Span(name="test", service_name="test", span_id="")
        assert span.span_id != ""

    def test_span_id_validation_none(self):
        span = Span(name="test", service_name="test", span_id=None)
        assert span.span_id is not None

    def test_span_id_validation_too_long(self):
        long_id = "a" * 200
        span = Span(name="test", service_name="test", span_id=long_id)
        assert span.span_id != long_id

    def test_parent_span_id_truncated(self):
        long_parent_id = "a" * 200
        span = Span(name="test", service_name="test", parent_span_id=long_parent_id)
        assert len(span.parent_span_id) == 128

    def test_parent_span_id_none(self):
        span = Span(name="test", service_name="test", parent_span_id=None)
        assert span.parent_span_id is None

    def test_set_attribute_with_empty_key(self):
        span = Span(name="test", service_name="test")
        span.set_attribute("", "value")
        assert "" not in span.attributes

    def test_set_attribute_with_long_key(self):
        span = Span(name="test", service_name="test")
        long_key = "a" * 200
        span.set_attribute(long_key, "value")
        assert len(list(span.attributes.keys())[0]) == 128

    def test_set_attribute_with_long_string_value(self):
        span = Span(name="test", service_name="test")
        long_value = "a" * 2000
        span.set_attribute("key", long_value)
        assert len(span.attributes["key"]) == 1024

    def test_set_attribute_with_non_string_value(self):
        span = Span(name="test", service_name="test")
        span.set_attribute("count", 42)
        assert span.attributes["count"] == 42

    def test_set_status_with_long_status(self):
        span = Span(name="test", service_name="test")
        long_status = "a" * 300
        span.set_status(long_status)
        assert len(span.status) == 256

    def test_set_status_with_long_message(self):
        span = Span(name="test", service_name="test")
        long_message = "a" * 2000
        span.set_status("error", long_message)
        assert len(span.status_message) == 1024

    def test_attributes_sanitized_on_init(self):
        long_key = "a" * 200
        long_value = "b" * 2000
        attributes = {long_key: long_value, "normal": "value"}
        span = Span(name="test", service_name="test", attributes=attributes)
        assert len(span.attributes) == 2
        sanitized_keys = list(span.attributes.keys())
        assert len(sanitized_keys[0]) == 128
        assert len(span.attributes[sanitized_keys[0]]) == 1024

    def test_attributes_none_on_init(self):
        span = Span(name="test", service_name="test", attributes=None)
        assert span.attributes == {}

    def test_span_kind_truncated(self):
        long_kind = "a" * 300
        span = Span(name="test", service_name="test", kind=long_kind)
        assert len(span.kind) == 256

    def test_span_kind_none(self):
        span = Span(name="test", service_name="test", kind=None)
        assert span.kind is None


class TestRateLimiterBoundary:
    def test_rate_limiter_with_zero_requests(self):
        mock_app = MagicMock()
        middleware = RateLimiterMiddleware(mock_app, requests_per_minute=0)
        assert middleware.requests_per_minute == 60

    def test_rate_limiter_with_negative_requests(self):
        mock_app = MagicMock()
        middleware = RateLimiterMiddleware(mock_app, requests_per_minute=-10)
        assert middleware.requests_per_minute == 60

    def test_rate_limiter_with_non_integer(self):
        mock_app = MagicMock()
        middleware = RateLimiterMiddleware(mock_app, requests_per_minute="invalid")
        assert middleware.requests_per_minute == 60

    def test_rate_limiter_with_valid_value(self):
        mock_app = MagicMock()
        middleware = RateLimiterMiddleware(mock_app, requests_per_minute=100)
        assert middleware.requests_per_minute == 100


class TestGenerateIdBoundary:
    def test_generate_trace_id_returns_valid_uuid(self):
        trace_id = generate_trace_id()
        assert len(trace_id) == 36
        assert "-" in trace_id

    def test_generate_span_id_returns_valid_uuid(self):
        span_id = generate_span_id()
        assert len(span_id) == 36
        assert "-" in span_id

    def test_generate_trace_id_unique(self):
        ids = [generate_trace_id() for _ in range(100)]
        assert len(set(ids)) == 100

    def test_generate_span_id_unique(self):
        ids = [generate_span_id() for _ in range(100)]
        assert len(set(ids)) == 100
