from __future__ import annotations

import logging
import time
from typing import Any

from streamsql.workers.celery_app import app
from streamsql.core.config import ConfigManager
from streamsql.core.models import generate_id

logger = logging.getLogger(__name__)


@app.task(bind=True, name="streamsql.workers.tasks.crawl_metadata")
def crawl_metadata(
    self,
    data_source_config: dict[str, Any],
    scan_tables: list[str] | None = None,
    sample_size: int = 1000,
) -> dict[str, Any]:
    from streamsql.services.metadata_service import MetadataService

    logger.info(f"Starting metadata crawl for {data_source_config}")

    try:
        service = MetadataService()
        result = service.crawl_data_source(
            data_source_config=data_source_config,
            scan_tables=scan_tables,
            sample_size=sample_size,
        )

        logger.info(f"Metadata crawl completed: {result.get('tables_scanned', 0)} tables scanned")
        return {
            "status": "success",
            "task_id": self.request.id,
            "result": result,
        }
    except Exception as e:
        logger.error(f"Metadata crawl failed: {str(e)}")
        self.update_state(state="FAILURE", meta={"error": str(e)})
        raise


@app.task(bind=True, name="streamsql.workers.tasks.start_cdc_capture")
def start_cdc_capture(
    self,
    source_config: dict[str, Any],
    output_config: dict[str, Any],
    serializer_format: str = "json",
    duration_seconds: int = 3600,
) -> dict[str, Any]:
    from streamsql.services.cdc_service import CDCService

    logger.info(f"Starting CDC capture: {source_config}")

    try:
        service = CDCService()
        capture_info = service.create_capture(
            source_config=source_config,
            output_config=output_config,
            serializer_format=serializer_format,
        )

        capture_info["task_id"] = self.request.id
        capture_info["started_at"] = time.time()
        capture_info["duration_seconds"] = duration_seconds

        return {
            "status": "running",
            "capture_info": capture_info,
        }
    except Exception as e:
        logger.error(f"CDC capture failed: {str(e)}")
        raise


@app.task(bind=True, name="streamsql.workers.tasks.parse_sql_batch")
def parse_sql_batch(
    self,
    sql_statements: list[str],
    optimize: bool = True,
) -> dict[str, Any]:
    from streamsql.services.query_service import QueryService

    logger.info(f"Parsing batch of {len(sql_statements)} SQL statements")

    try:
        service = QueryService()
        results = []

        for i, sql in enumerate(sql_statements):
            try:
                result = service.parse_sql(sql=sql, optimize=optimize)
                results.append({"index": i, "sql": sql, "result": result, "success": True})
            except Exception as e:
                results.append({"index": i, "sql": sql, "error": str(e), "success": False})

            self.update_state(
                state="PROGRESS",
                meta={"current": i + 1, "total": len(sql_statements)},
            )

        return {
            "status": "completed",
            "task_id": self.request.id,
            "total": len(sql_statements),
            "success_count": sum(1 for r in results if r["success"]),
            "failed_count": sum(1 for r in results if not r["success"]),
            "results": results,
        }
    except Exception as e:
        logger.error(f"SQL batch parse failed: {str(e)}")
        raise


@app.task(bind=True, name="streamsql.workers.tasks.build_vector_index")
def build_vector_index(
    self,
    texts: list[str],
    index_type: str = "hnsw",
    embedding_model: str = "mock",
    distance_metric: str = "cosine",
) -> dict[str, Any]:
    from streamsql.services.vector_service import VectorService

    logger.info(f"Building vector index for {len(texts)} texts")

    try:
        service = VectorService()
        result = service.build_index(
            texts=texts,
            index_type=index_type,
            embedding_model=embedding_model,
            distance_metric=distance_metric,
        )

        return {
            "status": "success",
            "task_id": self.request.id,
            "index_info": result,
        }
    except Exception as e:
        logger.error(f"Vector index build failed: {str(e)}")
        raise


@app.task(bind=True, name="streamsql.workers.tasks.run_lifecycle_cycle")
def run_lifecycle_cycle(
    self,
    table_name: str | None = None,
) -> dict[str, Any]:
    from streamsql.services.lifecycle_service import LifecycleService

    logger.info(f"Running lifecycle cycle for {table_name or 'all tables'}")

    try:
        service = LifecycleService()
        current_time = int(time.time() * 1000)

        migrate_result = service.migrate_data(
            table_name or "default",
            current_time_ms=current_time,
        )

        cleanup_result = service.cleanup_expired(
            table_name,
            current_time_ms=current_time,
        )

        return {
            "status": "success",
            "task_id": self.request.id,
            "migration": migrate_result,
            "cleanup": cleanup_result,
            "timestamp": current_time,
        }
    except Exception as e:
        logger.error(f"Lifecycle cycle failed: {str(e)}")
        raise


@app.task(bind=True, name="streamsql.workers.tasks.extract_lineage")
def extract_lineage(
    self,
    sql_statements: list[str],
) -> dict[str, Any]:
    from streamsql.services.lineage_service import LineageService

    logger.info(f"Extracting lineage from {len(sql_statements)} SQL statements")

    try:
        service = LineageService()
        result = service.extract_from_sql(sql=sql_statements)

        return {
            "status": "success",
            "task_id": self.request.id,
            "lineage_result": result,
        }
    except Exception as e:
        logger.error(f"Lineage extraction failed: {str(e)}")
        raise


@app.task(bind=True, name="streamsql.workers.tasks.compress_timeseries")
def compress_timeseries(
    self,
    timestamps: list[int],
    values: list[float],
    series_name: str | None = None,
    encoder_type: str = "gorilla",
) -> dict[str, Any]:
    from streamsql.services.timeseries_service import TimeSeriesService

    logger.info(f"Compressing {len(timestamps)} timeseries points")

    try:
        service = TimeSeriesService()
        name = series_name or generate_id("ts")

        service.add_data_batch(name, timestamps, values)
        result = service.compress(name, encoder_type)

        return {
            "status": "success",
            "task_id": self.request.id,
            "series_name": name,
            "compression_result": result,
        }
    except Exception as e:
        logger.error(f"Timeseries compression failed: {str(e)}")
        raise


@app.task(bind=True, name="streamsql.workers.tasks.validate_data_quality")
def validate_data_quality(
    self,
    data: list[dict[str, Any]],
    table_name: str = "",
    rule_ids: list[str] | None = None,
) -> dict[str, Any]:
    from streamsql.services.quality_service import QualityService

    logger.info(f"Validating data quality for {len(data)} rows in {table_name}")

    try:
        service = QualityService()
        result = service.validate(
            data=data,
            table_name=table_name,
            rule_ids=rule_ids,
        )

        return {
            "status": "completed",
            "task_id": self.request.id,
            "table": table_name,
            "validation_result": result,
        }
    except Exception as e:
        logger.error(f"Data quality validation failed: {str(e)}")
        raise


@app.task(bind=True, name="streamsql.workers.tasks.cleanup_anomalies")
def cleanup_anomalies(
    self,
    table_name: str | None = None,
    max_age_seconds: int = 86400 * 7,
) -> dict[str, Any]:
    from streamsql.services.quality_service import QualityService

    logger.info(f"Cleaning up anomalies for {table_name or 'all tables'}")

    try:
        service = QualityService()
        count = service.clear_anomalies(table_name)

        return {
            "status": "success",
            "task_id": self.request.id,
            "cleared_count": count,
            "table": table_name,
        }
    except Exception as e:
        logger.error(f"Anomaly cleanup failed: {str(e)}")
        raise


@app.task(bind=True, name="streamsql.workers.tasks.generate_quality_report")
def generate_quality_report(
    self,
    table_name: str | None = None,
) -> dict[str, Any]:
    from streamsql.services.quality_service import QualityService

    logger.info(f"Generating quality report for {table_name or 'all tables'}")

    try:
        service = QualityService()
        report = service.get_quality_report(table_name)

        return {
            "status": "success",
            "task_id": self.request.id,
            "table": table_name,
            "report": report,
            "generated_at": time.time(),
        }
    except Exception as e:
        logger.error(f"Quality report generation failed: {str(e)}")
        raise


@app.task(bind=True, name="streamsql.workers.tasks.batch_process")
def batch_process(
    self,
    module: str,
    operation: str,
    params: dict[str, Any],
) -> dict[str, Any]:
    logger.info(f"Running batch process: {module}.{operation}")

    task_map = {
        ("metadata", "crawl"): crawl_metadata,
        ("cdc", "capture"): start_cdc_capture,
        ("query", "parse"): parse_sql_batch,
        ("vector", "build_index"): build_vector_index,
        ("lifecycle", "run_cycle"): run_lifecycle_cycle,
        ("lineage", "extract"): extract_lineage,
        ("timeseries", "compress"): compress_timeseries,
        ("quality", "validate"): validate_data_quality,
    }

    task_func = task_map.get((module, operation))
    if not task_func:
        raise ValueError(f"Unknown batch operation: {module}.{operation}")

    result = task_func.apply_async(**params)

    return {
        "status": "submitted",
        "task_id": result.id,
        "module": module,
        "operation": operation,
    }
