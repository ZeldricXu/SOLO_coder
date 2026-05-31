import pytest
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from modules.monitoring_module import get_monitoring


def test_metrics_collector():
    monitoring = get_monitoring()
    collector = monitoring.collector

    collector.increment("test.counter")
    collector.set_gauge("test.gauge", 42.0)
    collector.record_histogram("test.histogram", 10.5)

    snapshot = collector.create_snapshot()
    assert snapshot is not None
    assert "counter.test.counter" in snapshot.metrics
    assert "gauge.test.gauge" in snapshot.metrics
    assert snapshot.metrics["gauge.test.gauge"] == 42.0


def test_alert_evaluator():
    monitoring = get_monitoring()
    evaluator = monitoring.alert_evaluator

    from modules.monitoring_module import MetricAlert

    alert = MetricAlert(
        alert_id="test_alert",
        metric_name="gauge.test.gauge",
        threshold=50.0,
        operator="gt",
        severity="warning",
        notification_channels=["webhook"],
    )
    evaluator.add_alert(alert)

    alerts = evaluator.get_alerts()
    assert len(alerts) >= 1
    assert any(a.alert_id == "test_alert" for a in alerts)


def test_alert_evaluation():
    monitoring = get_monitoring()
    collector = monitoring.collector
    evaluator = monitoring.alert_evaluator

    from modules.monitoring_module import MetricAlert

    alert = MetricAlert(
        alert_id="test_alert_2",
        metric_name="gauge.test_high",
        threshold=100.0,
        operator="gt",
        severity="critical",
    )
    evaluator.add_alert(alert)

    collector.set_gauge("test_high", 150.0)
    collector.create_snapshot()

    triggered = evaluator.evaluate()
    assert isinstance(triggered, list)


def test_acknowledge_alert():
    monitoring = get_monitoring()
    evaluator = monitoring.alert_evaluator

    success = evaluator.acknowledge_alert("test_alert")
    assert success is True


def test_timer():
    monitoring = get_monitoring()
    collector = monitoring.collector

    collector.start_timer("test.timer")
    import time
    time.sleep(0.01)
    elapsed = collector.stop_timer("test.timer")

    assert elapsed > 0
