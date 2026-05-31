import pytest
from datetime import datetime, timedelta
from src.modules import (
    NotificationManager, NotificationPriority, NotificationChannel,
    NotificationStatus, SuppressionStrategy, SuppressionRule
)


@pytest.fixture
def notification_manager():
    return NotificationManager()


def test_create_notification(notification_manager):
    notification = notification_manager.create_notification(
        title="Test",
        message="Test message",
        priority=NotificationPriority.HIGH,
        tags=["test", "alert"],
    )

    assert notification.title == "Test"
    assert notification.message == "Test message"
    assert notification.priority == NotificationPriority.HIGH
    assert notification.status == NotificationStatus.PENDING


@pytest.mark.asyncio
async def test_send_notification(notification_manager):
    notification = notification_manager.create_notification(
        title="Test",
        message="Test message",
        channels=[NotificationChannel.CONSOLE],
    )

    result = await notification_manager.send(notification)
    assert result.status in [NotificationStatus.SENT, NotificationStatus.SUPPRESSED]


@pytest.mark.asyncio
async def test_send_immediately(notification_manager):
    result = await notification_manager.send_immediately(
        title="Immediate",
        message="Immediate message",
        priority=NotificationPriority.LOW,
        channels=[NotificationChannel.CONSOLE],
    )

    assert result is not None


def test_add_and_remove_suppression_rule(notification_manager):
    rule = SuppressionRule(
        rule_id="rule_1",
        strategy=SuppressionStrategy.SILENCE,
        end_time=datetime.utcnow() + timedelta(hours=1),
    )

    notification_manager.add_suppression_rule(rule)
    assert len(notification_manager.get_suppression_rules()) == 1

    assert notification_manager.remove_suppression_rule("rule_1")
    assert len(notification_manager.get_suppression_rules()) == 0


def test_silence(notification_manager):
    rule = notification_manager.silence(duration=3600)
    assert rule.strategy == SuppressionStrategy.SILENCE
    assert rule.end_time > datetime.utcnow()


def test_get_stats(notification_manager):
    stats = notification_manager.get_stats()
    assert "total" in stats
    assert "by_status" in stats
