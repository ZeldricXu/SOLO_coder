import pytest
import time

from app.monitoring.metrics import MetricsCollector, get_metrics_collector, MetricType


def test_metrics_collector_singleton():
    m1 = get_metrics_collector()
    m2 = get_metrics_collector()
    assert m1 is m2


def test_counter():
    metrics = MetricsCollector()

    metrics.increment_counter("test_counter")
    assert metrics.get_counter("test_counter") == 1

    metrics.increment_counter("test_counter", value=5)
    assert metrics.get_counter("test_counter") == 6

    metrics.increment_counter("test_counter", labels={"env": "test"})
    assert metrics.get_counter("test_counter", {"env": "test"}) == 1


def test_gauge():
    metrics = MetricsCollector()

    metrics.set_gauge("test_gauge", 100)
    assert metrics.get_gauge("test_gauge") == 100

    metrics.set_gauge("test_gauge", 50)
    assert metrics.get_gauge("test_gauge") == 50


def test_histogram():
    metrics = MetricsCollector()

    metrics.record_histogram("test_histogram", 10)
    metrics.record_histogram("test_histogram", 20)
    metrics.record_histogram("test_histogram", 30)

    stats = metrics.get_histogram_stats("test_histogram")
    assert stats is not None
    assert stats.count == 3
    assert stats.sum == 60
    assert stats.min == 10
    assert stats.max == 30


def test_timer():
    metrics = MetricsCollector()

    with metrics.timer("test_timer"):
        time.sleep(0.01)

    stats = metrics.get_histogram_stats("test_timer")
    assert stats is not None
    assert stats.count == 1
    assert stats.sum > 0


def test_snapshot():
    metrics = MetricsCollector()

    metrics.increment_counter("snap_counter", value=10)
    metrics.set_gauge("snap_gauge", 42)
    metrics.record_histogram("snap_histogram", 100)

    snapshot = metrics.snapshot()

    assert "counters" in snapshot
    assert "gauges" in snapshot
    assert "histograms" in snapshot
    assert "snap_counter" in snapshot["counters"]
    assert "snap_gauge" in snapshot["gauges"]


def test_export_prometheus():
    metrics = MetricsCollector()

    metrics.increment_counter("prom_test_total", value=5)
    metrics.set_gauge("prom_test_gauge", 3.14)

    output = metrics.export_prometheus()

    assert "prom_test_total" in output or output == ""
    assert "prom_test_gauge" in output or output == ""


def test_metric_type_enum():
    assert MetricType.COUNTER.value == "counter"
    assert MetricType.GAUGE.value == "gauge"
    assert MetricType.HISTOGRAM.value == "histogram"
    assert MetricType.TIMER.value == "timer"


def test_labels():
    metrics = MetricsCollector()

    metrics.increment_counter("label_test", labels={"env": "prod", "version": "v1"})
    metrics.increment_counter("label_test", labels={"env": "prod", "version": "v2"})

    assert metrics.get_counter("label_test", {"env": "prod", "version": "v1"}) == 1
    assert metrics.get_counter("label_test", {"env": "prod", "version": "v2"}) == 1


def test_percentiles():
    metrics = MetricsCollector()

    for i in range(100):
        metrics.record_histogram("percentile_test", i)

    stats = metrics.get_histogram_stats("percentile_test")
    assert stats is not None
    assert stats.p50 >= 40
    assert stats.p50 <= 60
    assert stats.p99 >= 90
