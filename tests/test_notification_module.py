from __future__ import annotations
import pytest
import time
import asyncio
from unittest.mock import patch, MagicMock, AsyncMock, call
from typing import List, Dict, Any
import threading
import concurrent.futures

from conftest import MockNotificationService

from builders import (
    NotificationBuilder,
    NotificationChannelBuilder,
    SuppressionRuleBuilder,
    Priority,
    NotificationType,
    TestDataGenerator,
)


pytestmark = [pytest.mark.unit, pytest.mark.notification]


class TestNotificationPriority:
    def test_notification_priority_ordering(self):
        priority_order = {"low": 0, "medium": 1, "high": 2, "critical": 3}

        notifications = TestDataGenerator.create_notification_batch(4)

        sorted_notifs = sorted(
            notifications,
            key=lambda n: priority_order.get(n.get("priority", "low"), 0),
            reverse=True
        )

        assert sorted_notifs[0]["priority"] == "critical"
        assert sorted_notifs[1]["priority"] == "high"
        assert sorted_notifs[2]["priority"] == "medium"
        assert sorted_notifs[3]["priority"] == "low"

    def test_high_priority_notification_routing(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any],
        slack_channel: Dict[str, Any],
        email_channel: Dict[str, Any]
    ):
        notification_service.add_channel(webhook_channel)
        notification_service.add_channel(slack_channel)
        notification_service.add_channel(email_channel)

        critical_notif = NotificationBuilder() \
            .critical() \
            .with_title("CRITICAL: System Down") \
            .with_message("Production system is experiencing an outage") \
            .build()

        result = notification_service.send(critical_notif)

        assert result["status"] == "sent"
        assert len(result["channels"]) == 3
        channel_types = [c["type"] for c in result["channels"]]
        assert "webhook" in channel_types
        assert "slack" in channel_types
        assert "email" in channel_types

    def test_medium_priority_notification_routing(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any],
        slack_channel: Dict[str, Any],
        email_channel: Dict[str, Any]
    ):
        notification_service.add_channel(webhook_channel)
        notification_service.add_channel(slack_channel)
        notification_service.add_channel(email_channel)

        medium_notif = NotificationBuilder() \
            .warning() \
            .with_title("WARNING: High Memory Usage") \
            .with_message("Memory usage is above 80%") \
            .build()

        result = notification_service.send(medium_notif)

        assert result["status"] == "sent"
        assert len(result["channels"]) == 2
        channel_types = [c["type"] for c in result["channels"]]
        assert "webhook" in channel_types
        assert "slack" in channel_types
        assert "email" not in channel_types

    def test_low_priority_notification_routing(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any],
        slack_channel: Dict[str, Any],
        email_channel: Dict[str, Any]
    ):
        notification_service.add_channel(webhook_channel)
        notification_service.add_channel(slack_channel)
        notification_service.add_channel(email_channel)

        low_notif = NotificationBuilder() \
            .with_priority(Priority.LOW) \
            .with_title("INFO: Daily Report Ready") \
            .with_message("Daily performance report is available") \
            .build()

        result = notification_service.send(low_notif)

        assert result["status"] == "sent"
        assert len(result["channels"]) == 1
        assert result["channels"][0]["type"] == "webhook"

    def test_batch_notification_priority_sorting(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any]
    ):
        notification_service.add_channel(webhook_channel)

        notifications = TestDataGenerator.create_notification_batch(20)
        results = notification_service.send_batch(notifications)

        sent_priorities = []
        for notif in notification_service.sent_notifications:
            sent_priorities.append(notif["priority"])

        priority_order = {"low": 0, "medium": 1, "high": 2, "critical": 3}
        priority_values = [priority_order[p] for p in sent_priorities]

        for i in range(len(priority_values) - 1):
            assert priority_values[i] >= priority_values[i + 1]


class TestNotificationSuppression:
    def test_suppression_by_tags(
        self,
        notification_service: MockNotificationService,
        tag_suppression_rule: Dict[str, Any],
        webhook_channel: Dict[str, Any]
    ):
        notification_service.add_channel(webhook_channel)
        notification_service.add_suppression_rule(tag_suppression_rule)

        noisy_notif = NotificationBuilder() \
            .with_priority(Priority.HIGH) \
            .with_title("Noisy Alert") \
            .with_message("This is a noisy alert that should be suppressed") \
            .with_tags("noisy", "spam", "test") \
            .build()

        result = notification_service.send(noisy_notif)

        assert result["status"] == "suppressed"
        assert len(notification_service.suppressed_notifications) == 1
        assert len(notification_service.sent_notifications) == 0

    def test_no_suppression_when_tags_mismatch(
        self,
        notification_service: MockNotificationService,
        tag_suppression_rule: Dict[str, Any],
        webhook_channel: Dict[str, Any]
    ):
        notification_service.add_channel(webhook_channel)
        notification_service.add_suppression_rule(tag_suppression_rule)

        normal_notif = NotificationBuilder() \
            .with_priority(Priority.HIGH) \
            .with_title("Normal Alert") \
            .with_message("This alert should not be suppressed") \
            .with_tags("important", "production") \
            .build()

        result = notification_service.send(normal_notif)

        assert result["status"] == "sent"
        assert len(notification_service.suppressed_notifications) == 0
        assert len(notification_service.sent_notifications) == 1

    def test_suppression_by_source(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any]
    ):
        source_rule = SuppressionRuleBuilder() \
            .for_source("flaky-service") \
            .with_duration(300000) \
            .build()
        notification_service.add_channel(webhook_channel)
        notification_service.add_suppression_rule(source_rule)

        flaky_notif = NotificationBuilder() \
            .with_priority(Priority.MEDIUM) \
            .from_source("flaky-service") \
            .with_title("Flaky Service Alert") \
            .with_message("This should be suppressed") \
            .build()

        normal_notif = NotificationBuilder() \
            .with_priority(Priority.MEDIUM) \
            .from_source("stable-service") \
            .with_title("Stable Service Alert") \
            .with_message("This should not be suppressed") \
            .build()

        result1 = notification_service.send(flaky_notif)
        result2 = notification_service.send(normal_notif)

        assert result1["status"] == "suppressed"
        assert result2["status"] == "sent"

    def test_suppression_by_priority(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any]
    ):
        priority_rule = SuppressionRuleBuilder() \
            .for_priority(Priority.LOW) \
            .with_duration(60000) \
            .build()
        notification_service.add_channel(webhook_channel)
        notification_service.add_suppression_rule(priority_rule)

        low_notif = NotificationBuilder() \
            .with_priority(Priority.LOW) \
            .with_title("Low Priority Info") \
            .with_message("This should be suppressed") \
            .build()

        high_notif = NotificationBuilder() \
            .with_priority(Priority.HIGH) \
            .with_title("High Priority Alert") \
            .with_message("This should not be suppressed") \
            .build()

        result1 = notification_service.send(low_notif)
        result2 = notification_service.send(high_notif)

        assert result1["status"] == "suppressed"
        assert result2["status"] == "sent"

    def test_disabled_suppression_rule(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any]
    ):
        disabled_rule = SuppressionRuleBuilder() \
            .with_tags("noisy") \
            .disabled() \
            .build()
        notification_service.add_channel(webhook_channel)
        notification_service.add_suppression_rule(disabled_rule)

        noisy_notif = NotificationBuilder() \
            .with_tags("noisy") \
            .build()

        result = notification_service.send(noisy_notif)

        assert result["status"] == "sent"

    def test_multiple_suppression_rules(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any]
    ):
        tag_rule = SuppressionRuleBuilder() \
            .with_tags("noisy") \
            .build()
        source_rule = SuppressionRuleBuilder() \
            .for_source("test-source") \
            .build()
        notification_service.add_channel(webhook_channel)
        notification_service.add_suppression_rule(tag_rule)
        notification_service.add_suppression_rule(source_rule)

        notif1 = NotificationBuilder().with_tags("noisy").build()
        notif2 = NotificationBuilder().from_source("test-source").build()
        notif3 = NotificationBuilder().with_tags("normal").from_source("normal").build()

        r1 = notification_service.send(notif1)
        r2 = notification_service.send(notif2)
        r3 = notification_service.send(notif3)

        assert r1["status"] == "suppressed"
        assert r2["status"] == "suppressed"
        assert r3["status"] == "sent"


class TestNotificationChannelManagement:
    def test_add_multiple_channels(self, notification_service: MockNotificationService):
        channels = [
            NotificationChannelBuilder().as_webhook("http://webhook1.com").build(),
            NotificationChannelBuilder().as_webhook("http://webhook2.com").build(),
            NotificationChannelBuilder().as_slack("https://slack.com/hook").build(),
            NotificationChannelBuilder().as_email(["admin@test.com"]).build(),
            NotificationChannelBuilder().as_sms("+1234567890").build(),
        ]

        for channel in channels:
            notification_service.add_channel(channel)

        assert len(notification_service.channels) == 5

    def test_disabled_channel_not_used(
        self,
        notification_service: MockNotificationService,
        sample_notification: Dict[str, Any]
    ):
        enabled_channel = NotificationChannelBuilder() \
            .as_webhook("http://enabled.com") \
            .build()
        disabled_channel = NotificationChannelBuilder() \
            .as_webhook("http://disabled.com") \
            .disabled() \
            .build()

        notification_service.add_channel(enabled_channel)
        notification_service.add_channel(disabled_channel)

        result = notification_service.send(sample_notification)

        assert len(result["channels"]) == 1
        assert result["channels"][0]["channelId"] == enabled_channel["id"]
        assert notification_service.get_channel_call_count(disabled_channel["id"]) == 0

    def test_no_channels_configured(
        self,
        notification_service: MockNotificationService,
        sample_notification: Dict[str, Any]
    ):
        result = notification_service.send(sample_notification)

        assert result["status"] == "no_channels"
        assert len(result["channels"]) == 0


class TestNotificationConcurrencyIsolation:
    def test_concurrent_notification_sending_isolation(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any]
    ):
        notification_service.add_channel(webhook_channel)

        def send_notification(priority: Priority):
            notif = NotificationBuilder() \
                .with_priority(priority) \
                .with_title(f"Concurrent {priority.value} alert") \
                .build()
            return notification_service.send(notif)

        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
            futures = []
            priorities = list(Priority) * 10
            for priority in priorities:
                futures.append(executor.submit(send_notification, priority))

            results = [f.result() for f in futures]

        assert len(results) == 40
        assert len(notification_service.sent_notifications) == 40
        assert notification_service.get_channel_call_count(webhook_channel["id"]) == 40

    def test_concurrent_suppression_isolation(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any]
    ):
        suppression_rule = SuppressionRuleBuilder() \
            .with_tags("suppress-me") \
            .with_duration(60000) \
            .build()
        notification_service.add_channel(webhook_channel)
        notification_service.add_suppression_rule(suppression_rule)

        def send_mixed_notification(index: int):
            if index % 2 == 0:
                notif = NotificationBuilder() \
                    .with_tags("suppress-me") \
                    .with_title(f"Suppressed {index}") \
                    .build()
            else:
                notif = NotificationBuilder() \
                    .with_tags("normal") \
                    .with_title(f"Normal {index}") \
                    .build()
            return notification_service.send(notif)

        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
            futures = [executor.submit(send_mixed_notification, i) for i in range(100)]
            results = [f.result() for f in futures]

        suppressed_count = sum(1 for r in results if r["status"] == "suppressed")
        sent_count = sum(1 for r in results if r["status"] == "sent")

        assert suppressed_count == 50
        assert sent_count == 50
        assert len(notification_service.suppressed_notifications) == 50
        assert len(notification_service.sent_notifications) == 50

    def test_channel_call_count_accuracy_under_concurrency(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any],
        slack_channel: Dict[str, Any]
    ):
        notification_service.add_channel(webhook_channel)
        notification_service.add_channel(slack_channel)

        def send_high_priority():
            notif = NotificationBuilder() \
                .with_priority(Priority.HIGH) \
                .build()
            return notification_service.send(notif)

        with concurrent.futures.ThreadPoolExecutor(max_workers=20) as executor:
            futures = [executor.submit(send_high_priority) for _ in range(100)]
            [f.result() for f in futures]

        assert notification_service.get_channel_call_count(webhook_channel["id"]) == 100
        assert notification_service.get_channel_call_count(slack_channel["id"]) == 100

    def test_concurrent_batch_processing(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any]
    ):
        notification_service.add_channel(webhook_channel)

        def process_batch(batch_size: int):
            notifications = TestDataGenerator.create_notification_batch(batch_size)
            return notification_service.send_batch(notifications)

        with concurrent.futures.ThreadPoolExecutor(max_workers=5) as executor:
            futures = [executor.submit(process_batch, 10) for _ in range(10)]
            results = [f.result() for f in futures]

        total_processed = sum(len(r) for r in results)
        assert total_processed == 100
        assert len(notification_service.sent_notifications) == 100

    def test_concurrent_channel_modification_isolation(
        self,
        notification_service: MockNotificationService
    ):
        def add_channel(channel_id: int):
            channel = NotificationChannelBuilder() \
                .with_id(f"channel_{channel_id}") \
                .as_webhook(f"http://webhook{channel_id}.com") \
                .build()
            notification_service.add_channel(channel)
            return channel

        with concurrent.futures.ThreadPoolExecutor(max_workers=10) as executor:
            futures = [executor.submit(add_channel, i) for i in range(50)]
            channels = [f.result() for f in futures]

        assert len(notification_service.channels) == 50
        all_ids = [c["id"] for c in channels]
        assert len(all_ids) == len(set(all_ids))


class TestNotificationBatching:
    def test_batch_preserves_priority_order(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any]
    ):
        notification_service.add_channel(webhook_channel)

        notifications = [
            NotificationBuilder().with_priority(Priority.LOW).with_title("L1").build(),
            NotificationBuilder().with_priority(Priority.CRITICAL).with_title("C1").build(),
            NotificationBuilder().with_priority(Priority.MEDIUM).with_title("M1").build(),
            NotificationBuilder().with_priority(Priority.HIGH).with_title("H1").build(),
            NotificationBuilder().with_priority(Priority.LOW).with_title("L2").build(),
        ]

        results = notification_service.send_batch(notifications)

        priority_order = {"low": 0, "medium": 1, "high": 2, "critical": 3}
        result_priorities = []
        for notif in notification_service.sent_notifications:
            result_priorities.append(notif["priority"])

        result_values = [priority_order[p] for p in result_priorities]
        for i in range(len(result_values) - 1):
            assert result_values[i] >= result_values[i + 1]

    def test_empty_batch(self, notification_service: MockNotificationService):
        results = notification_service.send_batch([])
        assert results == []

    def test_large_batch_processing(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any]
    ):
        notification_service.add_channel(webhook_channel)
        notifications = TestDataGenerator.create_notification_batch(100)

        results = notification_service.send_batch(notifications)

        assert len(results) == 100
        assert len(notification_service.sent_notifications) == 100

    def test_batch_with_mixed_suppression(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any],
        tag_suppression_rule: Dict[str, Any]
    ):
        notification_service.add_channel(webhook_channel)
        notification_service.add_suppression_rule(tag_suppression_rule)

        notifications = []
        for i in range(20):
            if i % 2 == 0:
                notif = NotificationBuilder() \
                    .with_tags("noisy", "spam") \
                    .with_title(f"Suppressed {i}") \
                    .build()
            else:
                notif = NotificationBuilder() \
                    .with_tags("normal") \
                    .with_title(f"Sent {i}") \
                    .build()
            notifications.append(notif)

        results = notification_service.send_batch(notifications)

        suppressed_count = sum(1 for r in results if r["status"] == "suppressed")
        sent_count = sum(1 for r in results if r["status"] == "sent")

        assert suppressed_count == 10
        assert sent_count == 10


class TestNotificationEdgeCases:
    def test_notification_without_tags(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any]
    ):
        notif = NotificationBuilder() \
            .with_tags() \
            .build()

        notification_service.add_channel(webhook_channel)
        result = notification_service.send(notif)

        assert result["status"] == "sent"

    def test_notification_with_empty_message(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any]
    ):
        notif = NotificationBuilder() \
            .with_message("") \
            .build()

        notification_service.add_channel(webhook_channel)
        result = notification_service.send(notif)

        assert result["status"] == "sent"

    def test_suppression_with_partial_tag_match(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any]
    ):
        rule = SuppressionRuleBuilder() \
            .with_tags("critical", "production") \
            .build()
        notification_service.add_channel(webhook_channel)
        notification_service.add_suppression_rule(rule)

        notif = NotificationBuilder() \
            .with_tags("critical") \
            .build()

        result = notification_service.send(notif)
        assert result["status"] == "sent"

    def test_notification_id_uniqueness(
        self,
        notification_service: MockNotificationService,
        webhook_channel: Dict[str, Any]
    ):
        notification_service.add_channel(webhook_channel)
        notifications = TestDataGenerator.create_notification_batch(100)

        ids = [n["id"] for n in notifications]
        assert len(ids) == len(set(ids))

        for notif in notifications:
            notification_service.send(notif)

        result_ids = [r["notificationId"] for r in [notification_service.send(n) for n in notifications]]
        assert len(result_ids) == len(set(result_ids))
