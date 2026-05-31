import asyncio
from datetime import datetime
from typing import Any, Dict
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from src.models import Notification, NotificationChannel, NotificationStatus
from src.notification.notifier import (
    BaseNotificationProvider,
    DeliveryStatus,
    DingTalkProvider,
    EmailProvider,
    NotificationManager,
    SlackProvider,
    SMSProvider,
    WebhookProvider,
)


class TestNotificationProviders:
    @pytest.mark.asyncio
    async def test_email_provider_send(self):
        provider = EmailProvider(smtp_host="localhost", smtp_port=587)

        with patch.object(provider, "_send_email", new_callable=AsyncMock) as mock_send:
            mock_send.return_value = True

            notif = Notification(
                channel=NotificationChannel.EMAIL,
                recipient="test@example.com",
                subject="Test",
                content="Test content",
            )

            status = await provider.send(notif)
            assert status.success is True
            assert status.notification_id == notif.notification_id

    @pytest.mark.asyncio
    async def test_sms_provider_send(self):
        provider = SMSProvider(api_key="test_key")

        with patch.object(provider, "_send_sms", new_callable=AsyncMock) as mock_send:
            mock_send.return_value = True

            notif = Notification(
                channel=NotificationChannel.SMS,
                recipient="+1234567890",
                content="Test SMS",
            )

            status = await provider.send(notif)
            assert status.success is True

    @pytest.mark.asyncio
    async def test_webhook_provider_send(self):
        provider = WebhookProvider()

        with patch("aiohttp.ClientSession.post") as mock_post:
            mock_response = AsyncMock()
            mock_response.status = 200
            mock_response.json = AsyncMock(return_value={"ok": True})
            mock_post.return_value.__aenter__.return_value = mock_response

            notif = Notification(
                channel=NotificationChannel.WEBHOOK,
                recipient="https://example.com/webhook",
                content='{"test": "data"}',
            )

            status = await provider.send(notif)
            assert status.success is True

    @pytest.mark.asyncio
    async def test_slack_provider_send(self):
        provider = SlackProvider(webhook_url="https://hooks.slack.com/test")

        with patch.object(provider, "_send_slack", new_callable=AsyncMock) as mock_send:
            mock_send.return_value = True

            notif = Notification(
                channel=NotificationChannel.SLACK,
                recipient="#general",
                content="Test Slack message",
            )

            status = await provider.send(notif)
            assert status.success is True

    @pytest.mark.asyncio
    async def test_dingtalk_provider_send(self):
        provider = DingTalkProvider(access_token="test_token")

        with patch.object(provider, "_send_dingtalk", new_callable=AsyncMock) as mock_send:
            mock_send.return_value = True

            notif = Notification(
                channel=NotificationChannel.DINGTALK,
                recipient="user123",
                content="Test DingTalk message",
            )

            status = await provider.send(notif)
            assert status.success is True


class TestNotificationManager:
    @pytest.mark.asyncio
    async def test_create_notification(self):
        manager = NotificationManager()

        notif = manager.create_notification(
            channel=NotificationChannel.EMAIL,
            recipient="test@example.com",
            subject="Test",
            content="Test content",
        )

        assert notif.notification_id is not None
        assert notif.channel == NotificationChannel.EMAIL
        assert notif.status == NotificationStatus.PENDING

    @pytest.mark.asyncio
    async def test_send_notification_with_retry(self):
        manager = NotificationManager()
        provider = EmailProvider(smtp_host="localhost")

        attempt = 0

        async def failing_send(notif):
            nonlocal attempt
            attempt += 1
            if attempt < 3:
                return DeliveryStatus(
                    notification_id=notif.notification_id,
                    success=False,
                    error="Temporary failure",
                )
            return DeliveryStatus(
                notification_id=notif.notification_id,
                success=True,
            )

        with patch.object(provider, "send", side_effect=failing_send):
            manager.register_provider(NotificationChannel.EMAIL, provider)

            notif = manager.create_notification(
                channel=NotificationChannel.EMAIL,
                recipient="test@example.com",
                subject="Test",
                content="Test",
                max_retries=3,
            )

            result = await manager.send(notif.notification_id)
            assert result.success is True
            assert attempt == 3
            assert notif.retry_count == 2

    @pytest.mark.asyncio
    async def test_send_notification_failed_permanently(self):
        manager = NotificationManager()
        provider = EmailProvider(smtp_host="localhost")

        async def always_fail(notif):
            return DeliveryStatus(
                notification_id=notif.notification_id,
                success=False,
                error="Permanent failure",
            )

        with patch.object(provider, "send", side_effect=always_fail):
            manager.register_provider(NotificationChannel.EMAIL, provider)

            notif = manager.create_notification(
                channel=NotificationChannel.EMAIL,
                recipient="test@example.com",
                subject="Test",
                content="Test",
                max_retries=2,
            )

            result = await manager.send(notif.notification_id)
            assert result.success is False
            assert result.error == "Permanent failure"
            assert notif.status == NotificationStatus.FAILED

    @pytest.mark.asyncio
    async def test_get_delivery_status(self):
        manager = NotificationManager()

        notif = manager.create_notification(
            channel=NotificationChannel.EMAIL,
            recipient="test@example.com",
            subject="Test",
            content="Test",
        )

        status = manager.get_delivery_status(notif.notification_id)
        assert status is not None
        assert status.status == NotificationStatus.PENDING

    @pytest.mark.asyncio
    async def test_get_nonexistent_notification_status(self):
        manager = NotificationManager()
        status = manager.get_delivery_status("nonexistent")
        assert status is None

    @pytest.mark.asyncio
    async def test_batch_send(self):
        manager = NotificationManager()
        provider = EmailProvider(smtp_host="localhost")

        async def success_send(notif):
            return DeliveryStatus(
                notification_id=notif.notification_id,
                success=True,
            )

        with patch.object(provider, "send", side_effect=success_send):
            manager.register_provider(NotificationChannel.EMAIL, provider)

            notification_ids = []
            for i in range(3):
                notif = manager.create_notification(
                    channel=NotificationChannel.EMAIL,
                    recipient=f"user{i}@example.com",
                    subject=f"Test {i}",
                    content="Test content",
                )
                notification_ids.append(notif.notification_id)

            results = await manager.batch_send(notification_ids)
            assert len(results) == 3
            assert all(r.success for r in results)

    def test_get_notification_statistics(self):
        manager = NotificationManager()

        for i in range(5):
            notif = Notification(
                notification_id=f"notif_{i}",
                channel=NotificationChannel.EMAIL,
                recipient="test@example.com",
                content="Test",
                status=NotificationStatus.DELIVERED if i < 3 else NotificationStatus.FAILED,
            )
            manager._notifications[notif.notification_id] = notif

        stats = manager.get_statistics()
        assert stats["total"] == 5
        assert stats["by_status"]["delivered"] == 3
        assert stats["by_status"]["failed"] == 2
        assert stats["by_channel"]["email"] == 5

    @pytest.mark.asyncio
    async def test_retry_failed_notifications(self):
        manager = NotificationManager()
        provider = EmailProvider(smtp_host="localhost")

        call_count = 0

        async def succeed_after_fail(notif):
            nonlocal call_count
            call_count += 1
            if call_count > 2:
                return DeliveryStatus(
                    notification_id=notif.notification_id,
                    success=True,
                )
            return DeliveryStatus(
                notification_id=notif.notification_id,
                success=False,
                error="Fail",
            )

        with patch.object(provider, "send", side_effect=succeed_after_fail):
            manager.register_provider(NotificationChannel.EMAIL, provider)

            failed_notifs = []
            for i in range(2):
                notif = Notification(
                    notification_id=f"failed_{i}",
                    channel=NotificationChannel.EMAIL,
                    recipient="test@example.com",
                    content="Test",
                    status=NotificationStatus.FAILED,
                    retry_count=0,
                    max_retries=3,
                )
                manager._notifications[notif.notification_id] = notif
                failed_notifs.append(notif.notification_id)

            retried = await manager.retry_failed()
            assert len(retried) == 2
