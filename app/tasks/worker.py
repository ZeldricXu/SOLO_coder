from datetime import datetime
from typing import Any, Dict, List

from celery import Celery
from celery.schedules import crontab
from celery.utils.log import get_task_logger

from app.config.settings import get_settings
from app.monitoring.metrics import get_metrics_collector

logger = get_task_logger(__name__)
settings = get_settings()


def make_celery():
    broker_url = settings.redis_url or "redis://localhost:6379/0"
    backend_url = settings.redis_url or "redis://localhost:6379/1"

    app = Celery(
        "db_pool_platform",
        broker=broker_url,
        backend=backend_url,
        include=[
            "app.tasks.worker",
            "app.tasks.quality",
            "app.tasks.vulnerability"
        ]
    )

    app.conf.update(
        task_serializer="json",
        accept_content=["json"],
        result_serializer="json",
        timezone="Asia/Shanghai",
        enable_utc=True,
        task_track_started=True,
        task_time_limit=3600,
        task_soft_time_limit=3000,
        worker_prefetch_multiplier=1,
        worker_max_tasks_per_child=1000,
        beat_schedule={
            "metrics-snapshot-every-5-minutes": {
                "task": "app.tasks.worker.metrics_snapshot",
                "schedule": crontab(minute="*/5"),
            },
            "cleanup-old-traces-every-hour": {
                "task": "app.tasks.worker.cleanup_traces",
                "schedule": crontab(minute=0),
            },
            "check-storage-lifecycle-every-6-hours": {
                "task": "app.tasks.worker.check_storage_lifecycle",
                "schedule": crontab(minute=0, hour="*/6"),
            },
        }
    )

    return app


app = make_celery()


@app.task(name="app.tasks.worker.metrics_snapshot", bind=True)
def metrics_snapshot_task(self):
    """定期创建指标快照"""
    from app.monitoring.metrics import get_metrics_collector

    metrics = get_metrics_collector()
    snapshot = metrics.snapshot()

    metrics.increment_counter("celery_tasks_executed", labels={"task": "metrics_snapshot"})

    logger.info(f"Metrics snapshot created at {datetime.utcnow()}")
    logger.info(f"  Counters: {len(snapshot.get('counters', {}))}")
    logger.info(f"  Gauges: {len(snapshot.get('gauges', {}))}")
    logger.info(f"  Histograms: {len(snapshot.get('histograms', {}))}")

    return {
        "timestamp": datetime.utcnow().isoformat(),
        "snapshot": snapshot
    }


@app.task(name="app.tasks.worker.cleanup_traces", bind=True)
def cleanup_traces_task(self, max_age_hours: int = 24):
    """清理过期的链路追踪数据"""
    from app.monitoring.tracing import get_tracer

    tracer = get_tracer()
    removed = tracer.cleanup_old_traces(max_age_hours=max_age_hours)

    metrics = get_metrics_collector()
    metrics.increment_counter("celery_tasks_executed", labels={"task": "cleanup_traces"})
    metrics.increment_counter("traces_cleaned_up", value=removed)

    logger.info(f"Cleaned up {removed} old traces (max_age={max_age_hours}h)")

    return {
        "removed": removed,
        "max_age_hours": max_age_hours,
        "timestamp": datetime.utcnow().isoformat()
    }


@app.task(name="app.tasks.worker.check_storage_lifecycle", bind=True)
def check_storage_lifecycle_task(self):
    """检查存储生命周期规则"""
    from app.storage.manager import get_storage_manager

    storage_manager = get_storage_manager()
    results = storage_manager.apply_lifecycle_rules()

    metrics = get_metrics_collector()
    metrics.increment_counter("celery_tasks_executed", labels={"task": "check_storage_lifecycle"})
    metrics.increment_counter("files_archived", value=results.get("archived", 0))
    metrics.increment_counter("files_deleted", value=results.get("deleted", 0))

    logger.info(f"Storage lifecycle check: archived={results.get('archived', 0)}, "
                f"deleted={results.get('deleted', 0)}")

    return {
        "archived": results.get("archived", 0),
        "deleted": results.get("deleted", 0),
        "timestamp": datetime.utcnow().isoformat()
    }


@app.task(name="app.tasks.worker.process_request", bind=True)
def process_request_task(self, request_data: Dict[str, Any]):
    """异步处理请求"""
    import asyncio
    from app.core.processor import get_request_processor

    processor = get_request_processor()

    loop = asyncio.get_event_loop()
    if loop.is_running():
        result = asyncio.ensure_future(processor.execute_handler(request_data))
    else:
        result = loop.run_until_complete(processor.execute_handler(request_data))

    metrics = get_metrics_collector()
    metrics.increment_counter("async_requests_processed")

    logger.info(f"Async request processed: {self.request.id}")

    return result


@app.task(name="app.tasks.worker.batch_resource_operation", bind=True)
def batch_resource_operation_task(self, operations: List[Dict[str, Any]]):
    """批量处理资源操作"""
    from app.api.routes import _resources

    results = []
    for op in operations:
        resource_id = op.get("id")
        action = op.get("action")
        success = False
        message = None

        if resource_id in _resources:
            if action == "stop":
                _resources[resource_id]["status"] = "stopped"
                success = True
            elif action == "start":
                _resources[resource_id]["status"] = "running"
                success = True
            elif action == "delete":
                del _resources[resource_id]
                success = True
            else:
                message = f"Unknown action: {action}"
        else:
            message = "Resource not found"

        results.append({
            "id": resource_id,
            "success": success,
            "message": message
        })

    logger.info(f"Batch operation completed: {len(results)} operations")

    return {
        "task_id": self.request.id,
        "total_operations": len(results),
        "results": results
    }


@app.task(name="app.tasks.worker.generate_quality_report", bind=True)
def generate_quality_report_task(self, path: str, output_format: str = "json"):
    """异步生成代码质量报告"""
    from app.quality.gate import get_quality_gate

    gate = get_quality_gate()
    report = gate.check_quality(path)

    if output_format == "html":
        content = gate.generate_html_report(report)
        return {
            "format": "html",
            "content": content,
            "summary": report.to_dict()["summary"]
        }
    else:
        return report.to_dict()


@app.task(name="app.tasks.worker.analyze_vulnerabilities_task", bind=True)
def analyze_vulnerabilities_task(self, sbom_path: str):
    """异步分析漏洞"""
    from app.vulnerability.analyzer import analyze_sbom

    report = analyze_sbom(sbom_path)

    logger.info(f"Vulnerability analysis complete: {sbom_path}")

    return report


if __name__ == "__main__":
    app.start()
