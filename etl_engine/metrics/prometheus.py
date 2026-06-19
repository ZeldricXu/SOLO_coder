from prometheus_client import Counter, Gauge, Histogram, CollectorRegistry, start_http_server

from etl_engine.metrics.collector import ExecutionLog


class PrometheusExporter:
    def __init__(self, port: int = 9091, registry: CollectorRegistry | None = None) -> None:
        self._port = port
        self._registry = registry or CollectorRegistry()

        self._task_duration = Histogram(
            "etl_task_duration_seconds",
            "Duration of ETL task execution in seconds",
            ["pipeline", "task_type", "status"],
            registry=self._registry,
        )
        self._task_input_rows = Counter(
            "etl_task_input_rows",
            "Number of input rows processed by ETL task",
            ["pipeline", "task_name"],
            registry=self._registry,
        )
        self._task_output_rows = Counter(
            "etl_task_output_rows",
            "Number of output rows produced by ETL task",
            ["pipeline", "task_name"],
            registry=self._registry,
        )
        self._task_memory_peak = Gauge(
            "etl_task_memory_peak_mb",
            "Peak memory usage of ETL task in MB",
            ["pipeline", "task_name"],
            registry=self._registry,
        )
        self._quality_checks = Counter(
            "etl_quality_checks_total",
            "Total number of quality checks performed",
            ["pipeline", "passed"],
            registry=self._registry,
        )
        self._pipeline_duration = Histogram(
            "etl_pipeline_duration_seconds",
            "Duration of ETL pipeline execution in seconds",
            ["pipeline", "status"],
            registry=self._registry,
        )
        self._sla_breaches = Counter(
            "etl_sla_breaches_total",
            "Total number of SLA breaches",
            ["pipeline"],
            registry=self._registry,
        )

        self._streaming_messages_consumed = Counter(
            "etl_streaming_messages_consumed_total",
            "Total number of messages consumed from streaming source",
            ["pipeline", "topic"],
            registry=self._registry,
        )
        self._streaming_messages_produced = Counter(
            "etl_streaming_messages_produced_total",
            "Total number of messages produced to sink",
            ["pipeline", "sink_type"],
            registry=self._registry,
        )
        self._streaming_throughput = Gauge(
            "etl_streaming_throughput_messages_per_second",
            "Current streaming throughput in messages per second",
            ["pipeline"],
            registry=self._registry,
        )
        self._streaming_lag = Gauge(
            "etl_streaming_lag_messages",
            "Current consumer lag in messages",
            ["pipeline", "topic", "partition"],
            registry=self._registry,
        )
        self._streaming_watermark = Gauge(
            "etl_streaming_watermark_timestamp_seconds",
            "Current window watermark as Unix timestamp",
            ["pipeline", "window_type"],
            registry=self._registry,
        )
        self._streaming_window_processed = Counter(
            "etl_streaming_windows_processed_total",
            "Total number of windows processed",
            ["pipeline", "window_type"],
            registry=self._registry,
        )
        self._streaming_errors = Counter(
            "etl_streaming_errors_total",
            "Total number of streaming pipeline errors",
            ["pipeline", "error_type"],
            registry=self._registry,
        )
        self._streaming_running = Gauge(
            "etl_streaming_running",
            "Whether the streaming pipeline is currently running (1=running, 0=stopped)",
            ["pipeline"],
            registry=self._registry,
        )

        self._online_quality_checks = Counter(
            "etl_online_quality_checks_total",
            "Total number of online quality checks performed",
            ["pipeline", "checkpoint_id", "passed"],
            registry=self._registry,
        )
        self._online_quality_timeouts = Counter(
            "etl_online_quality_timeouts_total",
            "Total number of online quality check timeouts",
            ["pipeline", "checkpoint_id"],
            registry=self._registry,
        )
        self._online_quality_aborts = Counter(
            "etl_online_quality_aborts_total",
            "Total number of tasks aborted due to online quality failure",
            ["pipeline", "checkpoint_id"],
            registry=self._registry,
        )

    def record_task(self, log: ExecutionLog) -> None:
        self._task_duration.labels(
            pipeline=log.pipeline_name,
            task_type=log.task_type,
            status=log.status,
        ).observe(log.duration_seconds or 0)

        if log.input_rows is not None:
            self._task_input_rows.labels(
                pipeline=log.pipeline_name,
                task_name=log.task_name,
            ).inc(log.input_rows)

        if log.output_rows is not None:
            self._task_output_rows.labels(
                pipeline=log.pipeline_name,
                task_name=log.task_name,
            ).inc(log.output_rows)

        if log.memory_peak_mb is not None:
            self._task_memory_peak.labels(
                pipeline=log.pipeline_name,
                task_name=log.task_name,
            ).set(log.memory_peak_mb)

        if log.quality_passed is not None:
            self._quality_checks.labels(
                pipeline=log.pipeline_name,
                passed=str(log.quality_passed).lower(),
            ).inc()

    def record_pipeline(
        self,
        execution_id: str,
        pipeline_name: str,
        status: str,
        duration: float,
    ) -> None:
        self._pipeline_duration.labels(
            pipeline=pipeline_name,
            status=status,
        ).observe(duration)

    def record_sla_breach(self, pipeline_name: str) -> None:
        self._sla_breaches.labels(pipeline=pipeline_name).inc()

    def record_streaming_message_consumed(self, pipeline_name: str, topic: str, count: int = 1) -> None:
        self._streaming_messages_consumed.labels(
            pipeline=pipeline_name,
            topic=topic,
        ).inc(count)

    def record_streaming_message_produced(self, pipeline_name: str, sink_type: str, count: int = 1) -> None:
        self._streaming_messages_produced.labels(
            pipeline=pipeline_name,
            sink_type=sink_type,
        ).inc(count)

    def record_streaming_throughput(self, pipeline_name: str, messages_per_second: float) -> None:
        self._streaming_throughput.labels(pipeline=pipeline_name).set(messages_per_second)

    def record_streaming_lag(self, pipeline_name: str, topic: str, partition: str | int, lag: int) -> None:
        self._streaming_lag.labels(
            pipeline=pipeline_name,
            topic=topic,
            partition=str(partition),
        ).set(lag)

    def record_streaming_watermark(self, pipeline_name: str, window_type: str, timestamp: float) -> None:
        self._streaming_watermark.labels(
            pipeline=pipeline_name,
            window_type=window_type,
        ).set(timestamp)

    def record_streaming_window_processed(self, pipeline_name: str, window_type: str) -> None:
        self._streaming_window_processed.labels(
            pipeline=pipeline_name,
            window_type=window_type,
        ).inc()

    def record_streaming_error(self, pipeline_name: str, error_type: str) -> None:
        self._streaming_errors.labels(
            pipeline=pipeline_name,
            error_type=error_type,
        ).inc()

    def record_streaming_running_status(self, pipeline_name: str, is_running: bool) -> None:
        self._streaming_running.labels(pipeline=pipeline_name).set(1 if is_running else 0)

    def record_online_quality_check(
        self,
        pipeline_name: str,
        checkpoint_id: str,
        passed: bool,
    ) -> None:
        self._online_quality_checks.labels(
            pipeline=pipeline_name,
            checkpoint_id=checkpoint_id,
            passed=str(passed).lower(),
        ).inc()

    def record_online_quality_timeout(self, pipeline_name: str, checkpoint_id: str) -> None:
        self._online_quality_timeouts.labels(
            pipeline=pipeline_name,
            checkpoint_id=checkpoint_id,
        ).inc()

    def record_online_quality_abort(self, pipeline_name: str, checkpoint_id: str) -> None:
        self._online_quality_aborts.labels(
            pipeline=pipeline_name,
            checkpoint_id=checkpoint_id,
        ).inc()

    def start_server(self) -> None:
        start_http_server(self._port)

    def get_metrics(self) -> str:
        from prometheus_client import generate_latest

        return generate_latest(self._registry).decode("utf-8")
