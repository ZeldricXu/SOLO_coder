import pytest
from prometheus_client import Counter, Gauge, CollectorRegistry

from etl_engine.metrics.prometheus import PrometheusExporter


@pytest.fixture
def prometheus_exporter():
    registry = CollectorRegistry()
    return PrometheusExporter(port=0, registry=registry)


@pytest.mark.unit
class TestStreamingMetricsCreated:
    def test_streaming_metrics_are_defined(self, prometheus_exporter):
        exporter = prometheus_exporter

        assert hasattr(exporter, "_streaming_messages_consumed")
        assert hasattr(exporter, "_streaming_messages_produced")
        assert hasattr(exporter, "_streaming_throughput")
        assert hasattr(exporter, "_streaming_lag")
        assert hasattr(exporter, "_streaming_watermark")
        assert hasattr(exporter, "_streaming_window_processed")
        assert hasattr(exporter, "_streaming_errors")
        assert hasattr(exporter, "_streaming_running")

        assert isinstance(exporter._streaming_messages_consumed, Counter)
        assert isinstance(exporter._streaming_messages_produced, Counter)
        assert isinstance(exporter._streaming_throughput, Gauge)
        assert isinstance(exporter._streaming_lag, Gauge)
        assert isinstance(exporter._streaming_watermark, Gauge)
        assert isinstance(exporter._streaming_window_processed, Counter)
        assert isinstance(exporter._streaming_errors, Counter)
        assert isinstance(exporter._streaming_running, Gauge)

    def test_streaming_metric_names_correct(self, prometheus_exporter):
        exporter = prometheus_exporter

        assert "etl_streaming_messages_consumed" in exporter._streaming_messages_consumed._name
        assert "etl_streaming_messages_produced" in exporter._streaming_messages_produced._name
        assert "etl_streaming_throughput_messages_per_second" in exporter._streaming_throughput._name
        assert "etl_streaming_lag_messages" in exporter._streaming_lag._name
        assert "etl_streaming_watermark_timestamp_seconds" in exporter._streaming_watermark._name
        assert "etl_streaming_windows_processed" in exporter._streaming_window_processed._name
        assert "etl_streaming_errors" in exporter._streaming_errors._name
        assert "etl_streaming_running" in exporter._streaming_running._name

    def test_streaming_metric_labels_correct(self, prometheus_exporter):
        exporter = prometheus_exporter

        assert exporter._streaming_messages_consumed._labelnames == ("pipeline", "topic")
        assert exporter._streaming_messages_produced._labelnames == ("pipeline", "sink_type")
        assert exporter._streaming_throughput._labelnames == ("pipeline",)
        assert exporter._streaming_lag._labelnames == ("pipeline", "topic", "partition")
        assert exporter._streaming_watermark._labelnames == ("pipeline", "window_type")
        assert exporter._streaming_window_processed._labelnames == ("pipeline", "window_type")
        assert exporter._streaming_errors._labelnames == ("pipeline", "error_type")
        assert exporter._streaming_running._labelnames == ("pipeline",)

    def test_online_quality_metrics_are_defined(self, prometheus_exporter):
        exporter = prometheus_exporter

        assert hasattr(exporter, "_online_quality_checks")
        assert hasattr(exporter, "_online_quality_timeouts")
        assert hasattr(exporter, "_online_quality_aborts")

        assert isinstance(exporter._online_quality_checks, Counter)
        assert isinstance(exporter._online_quality_timeouts, Counter)
        assert isinstance(exporter._online_quality_aborts, Counter)

        assert "etl_online_quality_checks" in exporter._online_quality_checks._name
        assert "etl_online_quality_timeouts" in exporter._online_quality_timeouts._name
        assert "etl_online_quality_aborts" in exporter._online_quality_aborts._name

        assert exporter._online_quality_checks._labelnames == ("pipeline", "checkpoint_id", "passed")
        assert exporter._online_quality_timeouts._labelnames == ("pipeline", "checkpoint_id")
        assert exporter._online_quality_aborts._labelnames == ("pipeline", "checkpoint_id")

    def test_existing_metrics_still_present(self, prometheus_exporter):
        exporter = prometheus_exporter

        assert hasattr(exporter, "_task_duration")
        assert hasattr(exporter, "_task_input_rows")
        assert hasattr(exporter, "_task_output_rows")
        assert hasattr(exporter, "_task_memory_peak")
        assert hasattr(exporter, "_quality_checks")
        assert hasattr(exporter, "_pipeline_duration")
        assert hasattr(exporter, "_sla_breaches")


@pytest.mark.unit
class TestRecordStreamingMetrics:
    def test_record_streaming_message_consumed(self, prometheus_exporter):
        exporter = prometheus_exporter

        exporter.record_streaming_message_consumed(
            pipeline_name="test_pipeline",
            topic="test_topic",
            count=1,
        )

        metrics_output = exporter.get_metrics()
        assert "etl_streaming_messages_consumed_total" in metrics_output
        assert 'pipeline="test_pipeline"' in metrics_output
        assert 'topic="test_topic"' in metrics_output

    def test_record_streaming_message_consumed_multiple(self, prometheus_exporter):
        exporter = prometheus_exporter

        for _ in range(5):
            exporter.record_streaming_message_consumed(
                pipeline_name="test_pipeline",
                topic="test_topic",
                count=2,
            )

        metrics_output = exporter.get_metrics()
        assert "etl_streaming_messages_consumed_total" in metrics_output
        assert "10.0" in metrics_output

    def test_record_streaming_message_produced(self, prometheus_exporter):
        exporter = prometheus_exporter

        exporter.record_streaming_message_produced(
            pipeline_name="test_pipeline",
            sink_type="clickhouse",
            count=10,
        )

        metrics_output = exporter.get_metrics()
        assert "etl_streaming_messages_produced_total" in metrics_output
        assert 'sink_type="clickhouse"' in metrics_output

    def test_record_streaming_throughput(self, prometheus_exporter):
        exporter = prometheus_exporter

        exporter.record_streaming_throughput(
            pipeline_name="test_pipeline",
            messages_per_second=100.5,
        )

        metrics_output = exporter.get_metrics()
        assert "etl_streaming_throughput_messages_per_second" in metrics_output
        assert "100.5" in metrics_output

    def test_record_streaming_lag(self, prometheus_exporter):
        exporter = prometheus_exporter

        exporter.record_streaming_lag(
            pipeline_name="test_pipeline",
            topic="test_topic",
            partition=0,
            lag=50,
        )

        metrics_output = exporter.get_metrics()
        assert "etl_streaming_lag_messages" in metrics_output
        assert 'partition="0"' in metrics_output
        assert "50.0" in metrics_output

    def test_record_streaming_lag_string_partition(self, prometheus_exporter):
        exporter = prometheus_exporter

        exporter.record_streaming_lag(
            pipeline_name="test_pipeline",
            topic="test_topic",
            partition="partition_1",
            lag=25,
        )

        metrics_output = exporter.get_metrics()
        assert "etl_streaming_lag_messages" in metrics_output
        assert 'partition="partition_1"' in metrics_output

    def test_record_streaming_watermark(self, prometheus_exporter):
        exporter = prometheus_exporter
        import time

        timestamp = 1234567890.123
        exporter.record_streaming_watermark(
            pipeline_name="test_pipeline",
            window_type="tumbling",
            timestamp=timestamp,
        )

        metrics_output = exporter.get_metrics()
        assert "etl_streaming_watermark_timestamp_seconds" in metrics_output
        assert 'window_type="tumbling"' in metrics_output
        assert "1.234567890123e+09" in metrics_output or "1234567890.123" in metrics_output

    def test_record_streaming_window_processed(self, prometheus_exporter):
        exporter = prometheus_exporter

        for _ in range(3):
            exporter.record_streaming_window_processed(
                pipeline_name="test_pipeline",
                window_type="tumbling",
            )

        metrics_output = exporter.get_metrics()
        assert "etl_streaming_windows_processed_total" in metrics_output
        assert "3.0" in metrics_output

    def test_record_streaming_error(self, prometheus_exporter):
        exporter = prometheus_exporter

        exporter.record_streaming_error(
            pipeline_name="test_pipeline",
            error_type="connection_error",
        )

        metrics_output = exporter.get_metrics()
        assert "etl_streaming_errors_total" in metrics_output
        assert 'error_type="connection_error"' in metrics_output

    def test_record_streaming_running_status(self, prometheus_exporter):
        exporter = prometheus_exporter

        exporter.record_streaming_running_status(
            pipeline_name="test_pipeline",
            is_running=True,
        )

        metrics_output = exporter.get_metrics()
        assert "etl_streaming_running" in metrics_output
        assert "1.0" in metrics_output

        exporter.record_streaming_running_status(
            pipeline_name="test_pipeline",
            is_running=False,
        )

        metrics_output = exporter.get_metrics()
        assert "0.0" in metrics_output

    def test_all_streaming_metrics_in_output(self, prometheus_exporter):
        exporter = prometheus_exporter
        import time

        exporter.record_streaming_message_consumed("pipe1", "topic1", 5)
        exporter.record_streaming_message_produced("pipe1", "clickhouse", 5)
        exporter.record_streaming_throughput("pipe1", 100.0)
        exporter.record_streaming_lag("pipe1", "topic1", 0, 10)
        exporter.record_streaming_watermark("pipe1", "tumbling", time.time())
        exporter.record_streaming_window_processed("pipe1", "tumbling")
        exporter.record_streaming_error("pipe1", "transform_error")
        exporter.record_streaming_running_status("pipe1", True)

        metrics_output = exporter.get_metrics()

        assert "etl_streaming_messages_consumed_total" in metrics_output
        assert "etl_streaming_messages_produced_total" in metrics_output
        assert "etl_streaming_throughput_messages_per_second" in metrics_output
        assert "etl_streaming_lag_messages" in metrics_output
        assert "etl_streaming_watermark_timestamp_seconds" in metrics_output
        assert "etl_streaming_windows_processed_total" in metrics_output
        assert "etl_streaming_errors_total" in metrics_output
        assert "etl_streaming_running" in metrics_output


@pytest.mark.unit
class TestOnlineQualityMetrics:
    def test_record_online_quality_check_passed(self, prometheus_exporter):
        exporter = prometheus_exporter

        exporter.record_online_quality_check(
            pipeline_name="test_pipeline",
            checkpoint_id="checkpoint_1",
            passed=True,
        )

        metrics_output = exporter.get_metrics()
        assert "etl_online_quality_checks_total" in metrics_output
        assert 'checkpoint_id="checkpoint_1"' in metrics_output
        assert 'passed="true"' in metrics_output

    def test_record_online_quality_check_failed(self, prometheus_exporter):
        exporter = prometheus_exporter

        exporter.record_online_quality_check(
            pipeline_name="test_pipeline",
            checkpoint_id="checkpoint_1",
            passed=False,
        )

        metrics_output = exporter.get_metrics()
        assert 'passed="false"' in metrics_output

    def test_record_online_quality_check_multiple(self, prometheus_exporter):
        exporter = prometheus_exporter

        for _ in range(3):
            exporter.record_online_quality_check(
                pipeline_name="test_pipeline",
                checkpoint_id="checkpoint_1",
                passed=True,
            )
        for _ in range(2):
            exporter.record_online_quality_check(
                pipeline_name="test_pipeline",
                checkpoint_id="checkpoint_1",
                passed=False,
            )

        metrics_output = exporter.get_metrics()
        assert "etl_online_quality_checks_total" in metrics_output

    def test_record_online_quality_timeout(self, prometheus_exporter):
        exporter = prometheus_exporter

        exporter.record_online_quality_timeout(
            pipeline_name="test_pipeline",
            checkpoint_id="checkpoint_1",
        )

        metrics_output = exporter.get_metrics()
        assert "etl_online_quality_timeouts_total" in metrics_output
        assert 'checkpoint_id="checkpoint_1"' in metrics_output

    def test_record_online_quality_timeout_multiple(self, prometheus_exporter):
        exporter = prometheus_exporter

        for _ in range(5):
            exporter.record_online_quality_timeout(
                pipeline_name="test_pipeline",
                checkpoint_id="checkpoint_2",
            )

        metrics_output = exporter.get_metrics()
        assert "etl_online_quality_timeouts_total" in metrics_output

    def test_record_online_quality_abort(self, prometheus_exporter):
        exporter = prometheus_exporter

        exporter.record_online_quality_abort(
            pipeline_name="test_pipeline",
            checkpoint_id="checkpoint_3",
        )

        metrics_output = exporter.get_metrics()
        assert "etl_online_quality_aborts_total" in metrics_output
        assert 'checkpoint_id="checkpoint_3"' in metrics_output

    def test_record_online_quality_abort_multiple(self, prometheus_exporter):
        exporter = prometheus_exporter

        for _ in range(2):
            exporter.record_online_quality_abort(
                pipeline_name="test_pipeline",
                checkpoint_id="checkpoint_4",
            )

        metrics_output = exporter.get_metrics()
        assert "etl_online_quality_aborts_total" in metrics_output

    def test_all_online_quality_metrics_in_output(self, prometheus_exporter):
        exporter = prometheus_exporter

        exporter.record_online_quality_check("pipe1", "cp1", True)
        exporter.record_online_quality_check("pipe1", "cp1", False)
        exporter.record_online_quality_timeout("pipe1", "cp1")
        exporter.record_online_quality_abort("pipe1", "cp1")

        metrics_output = exporter.get_metrics()

        assert "etl_online_quality_checks_total" in metrics_output
        assert "etl_online_quality_timeouts_total" in metrics_output
        assert "etl_online_quality_aborts_total" in metrics_output

    def test_online_quality_counters_increment_correctly(self, prometheus_exporter):
        exporter = prometheus_exporter

        exporter.record_online_quality_check("pipe1", "cp1", True)
        exporter.record_online_quality_check("pipe1", "cp1", True)
        exporter.record_online_quality_check("pipe1", "cp1", False)
        exporter.record_online_quality_timeout("pipe1", "cp1")
        exporter.record_online_quality_timeout("pipe1", "cp1")
        exporter.record_online_quality_abort("pipe1", "cp1")

        metrics_output = exporter.get_metrics()

        assert 'checkpoint_id="cp1"' in metrics_output
        assert 'passed="true"' in metrics_output
        assert 'passed="false"' in metrics_output
