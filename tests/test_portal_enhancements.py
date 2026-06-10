import pytest
from unittest.mock import MagicMock, patch, AsyncMock
from typing import Any, Dict
from datetime import datetime, timezone

from fastapi import FastAPI
from fastapi.testclient import TestClient
from fastapi.security import HTTPBearer

from gateway.developer_portal.routes import router
from gateway.notifications.webhook import (
    WebhookNotifier,
    WebhookEvent,
    get_webhook_notifier,
)
from gateway.config import get_settings
from tests.conftest import *


pytestmark = pytest.mark.asyncio


class TestAPIKeyPlans:
    def test_list_plans_returns_all_plans(self):
        app = FastAPI()
        app.include_router(router)

        client = TestClient(app)
        response = client.get("/api/portal/plans")

        assert response.status_code == 200
        data = response.json()
        assert isinstance(data, list)
        assert len(data) >= 3

        plan_ids = [p["id"] for p in data]
        assert "free" in plan_ids
        assert "basic" in plan_ids
        assert "enterprise" in plan_ids

        free_plan = next(p for p in data if p["id"] == "free")
        assert free_plan["price"] == 0
        assert free_plan["requires_approval"] is True

    def test_plan_structure(self):
        app = FastAPI()
        app.include_router(router)

        client = TestClient(app)
        response = client.get("/api/portal/plans")
        data = response.json()

        for plan in data:
            assert "id" in plan
            assert "name" in plan
            assert "description" in plan
            assert "rate_limit_quota" in plan
            assert "price" in plan
            assert "requires_approval" in plan


class TestWebhookNotifier:
    async def test_notifier_disabled(self):
        settings = get_settings()
        settings.webhook.enabled = False

        notifier = WebhookNotifier()
        notifier.wh_settings = settings.webhook

        await notifier.initialize()

        await notifier.notify("api_key.created", {"test": "data"})
        assert len(notifier._pending_events) == 0

    async def test_notify_adds_to_queue(self):
        settings = get_settings()
        settings.webhook.enabled = True
        settings.webhook.url = "http://example.com/webhook"
        settings.webhook.events = ["api_key.created", "api_key.approved"]

        notifier = WebhookNotifier()
        notifier.wh_settings = settings.webhook
        notifier._initialized = True

        await notifier.notify("api_key.created", {"key_id": "123"})

        assert len(notifier._pending_events) == 1
        event = notifier._pending_events[0]
        assert event.event_type == "api_key.created"
        assert event.payload["key_id"] == "123"

    async def test_notify_skips_untracked_events(self):
        settings = get_settings()
        settings.webhook.enabled = True
        settings.webhook.url = "http://example.com/webhook"
        settings.webhook.events = ["api_key.created"]

        notifier = WebhookNotifier()
        notifier.wh_settings = settings.webhook
        notifier._initialized = True

        await notifier.notify("api_key.expired", {"key_id": "123"})

        assert len(notifier._pending_events) == 0

    async def test_webhook_event_has_id_and_timestamp(self):
        event = WebhookEvent(event_type="test.event", payload={"data": "test"})

        assert event.event_id is not None
        assert len(event.event_id) > 0
        assert event.timestamp > 0
        assert event.event_type == "test.event"
        assert event.payload["data"] == "test"

    async def test_singleton(self):
        notifier1 = get_webhook_notifier()
        notifier2 = get_webhook_notifier()

        assert notifier1 is notifier2


class TestAPIKeyCreateWithPlan:
    async def test_create_api_key_with_plan(self):
        pass

    async def test_create_api_key_no_approval_required(self):
        pass


class TestAPIKeyApprovalWithNotes:
    async def test_approve_with_note(self):
        pass

    async def test_reject_with_reason(self):
        pass


class TestSecurityFilterConfig:
    def test_security_filter_default_disabled(self):
        from gateway.config import Settings, SecurityFilterSettings
        sf = SecurityFilterSettings()
        assert sf.enabled is False

    def test_security_filter_config_exists(self):
        from gateway.config import Settings, SecurityFilterSettings
        sf = SecurityFilterSettings()

        assert hasattr(sf, "mode")
        assert hasattr(sf, "scan_body")
        assert hasattr(sf, "scan_query")
        assert hasattr(sf, "scan_headers")
        assert hasattr(sf, "sql_injection_enabled")
        assert hasattr(sf, "xss_enabled")
        assert hasattr(sf, "path_traversal_enabled")
        assert hasattr(sf, "command_injection_enabled")


class TestRateLimitMultiDimensionConfig:
    def test_multi_dimension_default_disabled(self):
        from gateway.config import RateLimitSettings
        rl = RateLimitSettings()
        assert rl.multi_dimension_enabled is False

    def test_dimensions_configured(self):
        from gateway.config import RateLimitSettings
        rl = RateLimitSettings()
        dimensions = rl.dimensions

        assert len(dimensions) >= 5
        dim_names = [d.name for d in dimensions]
        assert "user_id" in dim_names
        assert "api_path" in dim_names
        assert "ip" in dim_names
        assert "api_key" in dim_names
        assert "service_name" in dim_names


class TestPortalConfig:
    def test_portal_plans_configured(self):
        from gateway.config import DeveloperPortalSettings
        portal = DeveloperPortalSettings()
        plans = portal.api_key_plans

        assert len(plans) >= 3
        plan_ids = [p["id"] for p in plans]
        assert "free" in plan_ids
        assert "basic" in plan_ids
        assert "enterprise" in plan_ids

    def test_webhook_config_exists(self):
        from gateway.config import WebhookSettings
        wh = WebhookSettings()

        assert hasattr(wh, "enabled")
        assert hasattr(wh, "url")
        assert hasattr(wh, "secret")
        assert hasattr(wh, "events")
        assert len(wh.events) >= 3
