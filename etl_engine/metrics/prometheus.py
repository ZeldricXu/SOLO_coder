from prometheus_client import Counter, Gauge, Histogram, start_http_server

from etl_engine.metrics.collector import ExecutionLog


class PrometheusExporter:
    def __init__(self, port: int = 9091) -> None:
        self._port = port

        self._task_duration = Histogram(
            "etl_task_duration_seconds",
            "Duration of ETL task execution in seconds",
            ["pipeline", "task_type", "status"],
        )
        self._task_input_rows = Counter(
            "etl_task_input_rows",
            "Number of input rows processed by ETL task",
            ["pipeline", "task_name"],
        )
        self._task_output_rows = Counter(
            "etl_task_output_rows",
            "Number of output rows produced by ETL task",
            ["pipeline", "task_name"],
        )
        self._task_memory_peak = Gauge(
            "etl_task_memory_peak_mb",
            "Peak memory usage of ETL task in MB",
            ["pipeline", "task_name"],
        )
        self._quality_checks = Counter(
            "etl_quality_checks_total",
            "Total number of quality checks performed",
            ["pipeline", "passed"],
        )
        self._pipeline_duration = Histogram(
            "etl_pipeline_duration_seconds",
            "Duration of ETL pipeline execution in seconds",
            ["pipeline", "status"],
        )
        self._sla_breaches = Counter(
            "etl_sla_breaches_total",
            "Total number of SLA breaches",
            ["pipeline"],
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

    def start_server(self) -> None:
        start_http_server(self._port)

    def get_metrics(self) -> str:
        from prometheus_client import generate_latest

        return generate_latest().decode("utf-8")
