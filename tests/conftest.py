import os
import sys

import pytest

project_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
src_path = os.path.join(project_root, "src")
if src_path not in sys.path:
    sys.path.insert(0, src_path)


@pytest.fixture
def event_bus():
    from platform_engineer.core.events import EventBus

    return EventBus()


@pytest.fixture
def config_manager():
    from platform_engineer.config import ConfigManager
    from platform_engineer.config.sources import MemorySource

    manager = ConfigManager()
    manager.add_source(MemorySource({"test": {"key": "value"}}), priority=10)
    manager.load_all()
    return manager


@pytest.fixture
def trace_collector():
    from platform_engineer.tracing import TraceCollector

    return TraceCollector(service_name="test-service")


@pytest.fixture
def notification_manager():
    from platform_engineer.notification import NotificationManager
    from platform_engineer.notification.channels import ConsoleChannel

    manager = NotificationManager()
    manager.register_channel("console", ConsoleChannel())
    return manager


@pytest.fixture
def anomaly_detector():
    from platform_engineer.anomaly_detection import AnomalyDetector

    return AnomalyDetector()


@pytest.fixture
def slo_manager():
    from platform_engineer.slo import SLOManager

    return SLOManager()


@pytest.fixture
def topology_builder():
    from platform_engineer.topology import TopologyBuilder

    return TopologyBuilder()


@pytest.fixture
def api_gateway():
    from platform_engineer.gateway import APIGateway

    return APIGateway()
