from __future__ import annotations
import pytest
import pytest_asyncio
import asyncio
import time
import threading
import concurrent.futures
from unittest.mock import MagicMock, patch, AsyncMock
from typing import AsyncGenerator, Dict, Any, List, Callable
from contextlib import asynccontextmanager

import sys
import os
sys.path.insert(0, os.path.dirname(__file__))

from builders import (
    TraceSpanBuilder,
    SamplingStrategyBuilder,
    NotificationBuilder,
    NotificationChannelBuilder,
    SuppressionRuleBuilder,
    ProfilingSessionBuilder,
    Priority,
    NotificationType,
    SamplingType,
    ProfileType,
    TestDataGenerator,
)


def pytest_configure(config):
    config.addinivalue_line(
        "markers", "unit: Unit tests that don't require external services"
    )
    config.addinivalue_line(
        "markers", "integration: Integration tests that require running services"
    )
    config.addinivalue_line(
        "markers", "tracing: Tests for distributed tracing module"
    )
    config.addinivalue_line(
        "markers", "notification: Tests for notification module"
    )
    config.addinivalue_line(
        "markers", "profiling: Tests for profiling module"
    )
    config.addinivalue_line(
        "markers", "slow: Tests that are slow to execute"
    )


@pytest.fixture
def mock_http_client():
    with patch("requests.Session") as mock_session:
        client = mock_session.return_value
        yield client


@pytest.fixture
def mock_async_http_client():
    mock_client = MagicMock()
    mock_client.get = AsyncMock()
    mock_client.post = AsyncMock()
    mock_client.put = AsyncMock()
    mock_client.delete = AsyncMock()
    return mock_client


class MockTracingService:
    def __init__(self):
        self.spans = []
        self.traces = {}
        self.strategies = []
        self.sampling_decisions = {}

    def collect_span(self, span: Dict[str, Any]) -> bool:
        self.spans.append(span)
        trace_id = span["traceId"]
        if trace_id not in self.traces:
            self.traces[trace_id] = []
        self.traces[trace_id].append(span)
        return True

    def get_trace(self, trace_id: str) -> List[Dict[str, Any]]:
        return self.traces.get(trace_id, [])

    def add_strategy(self, strategy: Dict[str, Any]) -> None:
        self.strategies.append(strategy)

    def should_sample_head(self, span: Dict[str, Any]) -> bool:
        for strategy in self.strategies:
            if strategy.get("type") != "head":
                continue
            if not strategy.get("enabled", True):
                continue
            rule = strategy.get("rule", {})
            if "serviceName" in rule and rule["serviceName"] != span.get("serviceName"):
                continue
            if "spanName" in rule and rule["spanName"] != span.get("name"):
                continue
            sample_rate = rule.get("sampleRate", 1.0)
            return hash(span["spanId"]) % 1000 < sample_rate * 1000
        return True

    def should_sample_tail(self, trace_id: str) -> bool:
        spans = self.traces.get(trace_id, [])
        for strategy in self.strategies:
            if strategy.get("type") != "tail":
                continue
            if not strategy.get("enabled", True):
                continue
            rule = strategy.get("rule", {})
            if rule.get("errorOnly"):
                has_error = any(s.get("status") == "ERROR" for s in spans)
                if not has_error:
                    return False
            if "minDuration" in rule:
                total_duration = sum(s.get("duration", 0) for s in spans)
                if total_duration < rule["minDuration"]:
                    return False
            sample_rate = rule.get("sampleRate", 1.0)
            return hash(trace_id) % 1000 < sample_rate * 1000
        return True

    def finalize_trace(self, trace_id: str) -> Dict[str, Any]:
        spans = self.traces.get(trace_id, [])
        sampled = self.should_sample_tail(trace_id)
        self.sampling_decisions[trace_id] = sampled
        return {
            "traceId": trace_id,
            "spanCount": len(spans),
            "sampled": sampled,
            "totalDuration": sum(s.get("duration", 0) for s in spans)
        }


class MockNotificationService:
    def __init__(self):
        self.channels = {}
        self.suppression_rules = {}
        self.sent_notifications = []
        self.suppressed_notifications = []
        self.channel_calls = {}

    def add_channel(self, channel: Dict[str, Any]) -> None:
        self.channels[channel["id"]] = channel
        self.channel_calls[channel["id"]] = 0

    def add_suppression_rule(self, rule: Dict[str, Any]) -> None:
        self.suppression_rules[rule["id"]] = rule

    def _match_suppression(self, notification: Dict[str, Any]) -> bool:
        for rule in self.suppression_rules.values():
            if not rule.get("enabled", True):
                continue
            matcher = rule.get("matcher", {})
            if "source" in matcher and matcher["source"] != notification.get("source"):
                continue
            if "priority" in matcher and matcher["priority"] != notification.get("priority"):
                continue
            if "tags" in matcher:
                notif_tags = set(notification.get("tags", []))
                rule_tags = set(matcher["tags"])
                if not rule_tags.issubset(notif_tags):
                    continue
            return True
        return False

    def _get_eligible_channels(self, notification: Dict[str, Any]) -> List[Dict[str, Any]]:
        priority_order = {"low": 0, "medium": 1, "high": 2, "critical": 3}
        notif_priority = priority_order.get(notification.get("priority", "low"), 0)
        eligible = []
        for channel in self.channels.values():
            if not channel.get("enabled", True):
                continue
            threshold = priority_order.get(channel.get("priorityThreshold", "low"), 0)
            if notif_priority >= threshold:
                eligible.append(channel)
        return eligible

    def send(self, notification: Dict[str, Any]) -> Dict[str, Any]:
        if self._match_suppression(notification):
            self.suppressed_notifications.append(notification)
            return {
                "status": "suppressed",
                "notificationId": notification["id"],
                "channels": []
            }

        channels = self._get_eligible_channels(notification)
        sent_channels = []

        for channel in channels:
            self.channel_calls[channel["id"]] += 1
            sent_channels.append({
                "channelId": channel["id"],
                "type": channel["type"],
                "status": "sent"
            })

        self.sent_notifications.append(notification)

        return {
            "status": "sent" if sent_channels else "no_channels",
            "notificationId": notification["id"],
            "channels": sent_channels
        }

    def send_batch(self, notifications: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        priority_order = {"low": 0, "medium": 1, "high": 2, "critical": 3}
        sorted_notifications = sorted(
            notifications,
            key=lambda n: priority_order.get(n.get("priority", "low"), 0),
            reverse=True
        )
        results = []
        for notification in sorted_notifications:
            result = self.send(notification)
            results.append(result)
        return results

    def get_channel_call_count(self, channel_id: str) -> int:
        return self.channel_calls.get(channel_id, 0)


class MockProfilingService:
    def __init__(self):
        self.sessions = {}
        self.flamegraphs = {}
        self.timeout_behavior = "degrade"
        self.max_concurrent_sessions = 10
        self.active_sessions = 0
        self._lock = threading.Lock()
        self._session_counter = 0

    def start_session(self, config: Dict[str, Any]) -> Dict[str, Any]:
        with self._lock:
            self._session_counter += 1
            session_id = f"prof_{int(time.time() * 1000)}_{self._session_counter}"
            duration = config.get("duration", 1000)

            if duration > 10000:
                if self.timeout_behavior == "reject":
                    raise ValueError("Session duration exceeds maximum allowed")
                elif self.timeout_behavior == "degrade":
                    duration = 10000

            if self.active_sessions >= self.max_concurrent_sessions:
                if self.timeout_behavior == "reject":
                    raise RuntimeError("Maximum concurrent sessions exceeded")
                else:
                    return {
                        "sessionId": session_id,
                        "status": "degraded",
                        "message": "Session queued due to high concurrency",
                        "actualDuration": duration
                    }

            self.active_sessions += 1
            self.sessions[session_id] = {
                "id": session_id,
                "type": config.get("type", "cpu"),
                "duration": duration,
                "status": "running",
                "startedAt": time.time() * 1000
            }

            return {
                "sessionId": session_id,
                "status": "started",
                "duration": duration
            }

    def get_session(self, session_id: str) -> Dict[str, Any]:
        return self.sessions.get(session_id)

    def stop_session(self, session_id: str) -> Dict[str, Any]:
        with self._lock:
            session = self.sessions.get(session_id)
            if session and session["status"] == "running":
                session["status"] = "completed"
                session["completedAt"] = time.time() * 1000
                self.active_sessions = max(0, self.active_sessions - 1)
                self._generate_flamegraph(session_id)
            return session

    def _generate_flamegraph(self, session_id: str) -> None:
        self.flamegraphs[session_id] = f"""
<svg width="1200" height="400" xmlns="http://www.w3.org/2000/svg">
    <rect width="1200" height="400" fill="#ffffff"/>
    <text x="10" y="30" font-family="Verdana" font-size="16">
        Flame Graph for session {session_id}
    </text>
    <rect x="50" y="100" width="1100" height="30" fill="#e74c3c" rx="2"/>
    <text x="60" y="120" font-family="Verdana" font-size="12" fill="#ffffff">
        app.js (42.5%)
    </text>
</svg>
"""

    def get_flamegraph(self, session_id: str) -> str:
        return self.flamegraphs.get(session_id, "")

    def compare_sessions(self, session_id1: str, session_id2: str) -> Dict[str, Any]:
        s1 = self.sessions.get(session_id1, {})
        s2 = self.sessions.get(session_id2, {})
        return {
            "session1": session_id1,
            "session2": session_id2,
            "durationDiff": s2.get("duration", 0) - s1.get("duration", 0),
            "typeMatch": s1.get("type") == s2.get("type"),
            "bothCompleted": s1.get("status") == "completed" and s2.get("status") == "completed"
        }

    def set_timeout_behavior(self, behavior: str) -> None:
        self.timeout_behavior = behavior

    def set_max_concurrent(self, max_sessions: int) -> None:
        self.max_concurrent_sessions = max_sessions


@pytest.fixture
def tracing_service() -> MockTracingService:
    return MockTracingService()


@pytest.fixture
def notification_service() -> MockNotificationService:
    return MockNotificationService()


@pytest.fixture
def profiling_service() -> MockProfilingService:
    return MockProfilingService()


@pytest.fixture
def sample_trace_spans() -> List[Dict[str, Any]]:
    return TestDataGenerator.create_trace_spans(5, "test-service")


@pytest.fixture
def error_trace_spans() -> List[Dict[str, Any]]:
    return TestDataGenerator.create_error_trace("error-service")


@pytest.fixture
def head_sampling_strategy() -> Dict[str, Any]:
    return SamplingStrategyBuilder() \
        .with_head_sampling(0.5) \
        .for_service("test-service") \
        .with_priority(1) \
        .build()


@pytest.fixture
def tail_sampling_strategy() -> Dict[str, Any]:
    return SamplingStrategyBuilder() \
        .with_tail_sampling(1.0) \
        .error_only() \
        .with_priority(2) \
        .build()


@pytest.fixture
def sample_notification() -> Dict[str, Any]:
    return NotificationBuilder() \
        .with_priority(Priority.HIGH) \
        .with_title("Test Alert") \
        .with_message("This is a test notification") \
        .with_tags("alert", "test") \
        .build()


@pytest.fixture
def webhook_channel() -> Dict[str, Any]:
    return NotificationChannelBuilder() \
        .as_webhook("http://localhost:8080/webhook") \
        .with_priority_threshold(Priority.LOW) \
        .build()


@pytest.fixture
def slack_channel() -> Dict[str, Any]:
    return NotificationChannelBuilder() \
        .as_slack("https://hooks.slack.com/xxx") \
        .with_priority_threshold(Priority.MEDIUM) \
        .build()


@pytest.fixture
def email_channel() -> Dict[str, Any]:
    return NotificationChannelBuilder() \
        .as_email(["admin@test.com"]) \
        .with_priority_threshold(Priority.CRITICAL) \
        .build()


@pytest.fixture
def tag_suppression_rule() -> Dict[str, Any]:
    return SuppressionRuleBuilder() \
        .with_tags("noisy", "spam") \
        .with_duration(60000) \
        .with_max_suppressions(10) \
        .build()


@pytest.fixture
def profiling_config_cpu() -> Dict[str, Any]:
    return ProfilingSessionBuilder() \
        .as_cpu() \
        .with_duration(1000) \
        .build()


@pytest.fixture
def profiling_config_memory() -> Dict[str, Any]:
    return ProfilingSessionBuilder() \
        .as_memory() \
        .with_duration(2000) \
        .build()


@pytest.fixture
def anomaly_metric_series() -> List[Dict[str, Any]]:
    return TestDataGenerator.create_anomaly_metric_series(
        "request.latency",
        normal_count=20,
        anomaly_count=3,
        base_value=100,
        anomaly_multiplier=5.0
    )
