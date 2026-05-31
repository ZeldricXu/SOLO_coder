import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_notification_list_channels(client: AsyncClient):
    response = await client.get("/api/v1/notifications/channels")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert len(data["data"]) > 0


@pytest.mark.asyncio
async def test_notification_create_template(client: AsyncClient):
    request = {
        "name": "test-template",
        "description": "Test notification template",
        "type": "text",
        "channels": ["email"],
        "subject": "Test: {{ name }}",
        "content": "Hello {{ name }}, your code is {{ code }}.",
        "variables": ["name", "code"],
        "default_variables": {"name": "User"},
    }
    response = await client.post("/api/v1/notifications/templates", json=request)
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 201
    assert "template_id" in data["data"]


@pytest.mark.asyncio
async def test_notification_list_templates(client: AsyncClient):
    response = await client.get("/api/v1/notifications/templates")
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert isinstance(data["data"], list)


@pytest.mark.asyncio
async def test_notification_send(client: AsyncClient):
    request = {
        "channel": "in_app",
        "recipients": ["user_123"],
        "subject": "Test Notification",
        "content": "This is a test notification",
        "variables": {},
        "priority": 2,
    }
    response = await client.post("/api/v1/notifications/send", json=request)
    assert response.status_code == 200
    data = response.json()
    assert data["code"] == 200
    assert "notification_id" in data["data"]
