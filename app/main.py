from fastapi import FastAPI, Request, HTTPException, Depends, BackgroundTasks
from fastapi.responses import JSONResponse, PlainTextResponse
from fastapi.middleware.cors import CORSMiddleware
from contextlib import asynccontextmanager
from typing import Optional, List, Dict, Any
from prometheus_client import generate_latest, CONTENT_TYPE_LATEST
import time
import uuid

from app.config import settings
from app.logging_module import get_logger
from app.monitoring import MetricsCollector, PrometheusExporter, HealthChecker, MetricQueryRequest
from app.notification import NotificationManager, NotificationRequest, SuppressionRule
from app.api_gateway import APIGateway, RouteConfig, GatewayRequest, BatchRequest
from app.adversarial import AdversarialGenerator, SafetyEvaluator, AttackConfig, AttackType
from app.prompt_experiment import (
    PromptVersionManager, ABExperimentManager,
    PromptCreateRequest, ExperimentConfig
)
from app.evaluation_dashboard import EvaluationDashboard, OfflineMetric, MetricType
from app.gpu_scheduler import GPUScheduler, TaskRequest

logger = get_logger(__name__)

metrics_collector = MetricsCollector()
prometheus_exporter = PrometheusExporter(metrics_collector, settings.PROMETHEUS_PORT)
health_checker = HealthChecker()
notification_manager = NotificationManager()
api_gateway = APIGateway()
adversarial_generator = AdversarialGenerator()
safety_evaluator = SafetyEvaluator()
prompt_manager = PromptVersionManager()
experiment_manager = ABExperimentManager()
dashboard = EvaluationDashboard()
gpu_scheduler = GPUScheduler()


@asynccontextmanager
async def lifespan(app: FastAPI):
    await notification_manager.start()
    await api_gateway.start()
    await prometheus_exporter.start()
    await health_checker.start()
    await dashboard.start()
    await gpu_scheduler.start()
    
    health_checker.register_check("notification_service", lambda: notification_manager.get_queue_size() >= 0)
    health_checker.register_check("api_gateway", lambda: len(api_gateway.get_health()["routes"]) >= 0)
    health_checker.register_check("gpu_scheduler", lambda: len(gpu_scheduler.get_gpu_status()) == settings.GPU_COUNT)
    
    api_gateway.register_route(RouteConfig(
        path="/api/v1/resources",
        target_url="http://localhost:8000/api/v1/resources",
        method="POST"
    ))
    
    logger.info(f"Application started", name=settings.APP_NAME)
    yield
    
    await notification_manager.stop()
    await api_gateway.stop()
    await prometheus_exporter.stop()
    await health_checker.stop()
    await dashboard.stop()
    await gpu_scheduler.stop()
    logger.info("Application shutdown complete")


app = FastAPI(
    title=settings.APP_NAME,
    description="Enterprise Notification Priority & Suppression Platform",
    version="1.0.0",
    lifespan=lifespan
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.middleware("http")
async def metrics_middleware(request: Request, call_next):
    start_time = time.time()
    try:
        response = await call_next(request)
        duration = time.time() - start_time
        metrics_collector.record_request(
            method=request.method,
            endpoint=request.url.path,
            status=response.status_code,
            duration=duration
        )
        return response
    except Exception as e:
        duration = time.time() - start_time
        metrics_collector.record_request(
            method=request.method,
            endpoint=request.url.path,
            status=500,
            duration=duration
        )
        raise


@app.get("/")
async def root():
    return {
        "name": settings.APP_NAME,
        "version": "1.0.0",
        "status": "running",
        "modules": {
            "notification": "active",
            "monitoring": "active",
            "api_gateway": "active",
            "adversarial": "active",
            "prompt_experiment": "active",
            "evaluation_dashboard": "active",
            "gpu_scheduler": "active"
        }
    }


@app.get("/health")
async def health():
    return health_checker.get_status()


@app.get("/metrics")
async def prometheus_metrics():
    data = metrics_collector.export_prometheus()
    return PlainTextResponse(content=data, media_type=CONTENT_TYPE_LATEST)


@app.post("/api/v1/metrics/query")
async def query_metrics(request: MetricQueryRequest):
    result = metrics_collector.query(request)
    return {"code": 200, "data": result.model_dump()}


@app.post("/api/v1/notifications")
async def send_notification(request: NotificationRequest):
    response = await notification_manager.send(request)
    return {"code": 201, "data": response.model_dump()}


@app.get("/api/v1/notifications/queue")
async def get_notification_queue():
    return {
        "code": 200,
        "data": {
            "size": notification_manager.get_queue_size(),
            "by_priority": notification_manager.get_pending_count()
        }
    }


@app.post("/api/v1/notifications/suppression-rules")
async def add_suppression_rule(rule: SuppressionRule):
    notification_manager.add_suppression_rule(rule)
    return {"code": 201, "data": {"rule_id": rule.rule_id}}


@app.delete("/api/v1/notifications/suppression-rules/{rule_id}")
async def remove_suppression_rule(rule_id: str):
    notification_manager.remove_suppression_rule(rule_id)
    return {"code": 200}


@app.post("/api/v1/resources")
async def create_resource(request: Dict[str, Any]):
    resource_id = f"rsc_{uuid.uuid4().hex[:8]}"
    return {
        "code": 201,
        "data": {
            "id": resource_id,
            "status": "provisioning",
            "config": request.get("config", {}),
            "labels": request.get("labels", {})
        }
    }


@app.get("/api/v1/resources/{resource_id}/status")
async def get_resource_status(resource_id: str):
    return {
        "code": 200,
        "data": {
            "id": resource_id,
            "status": "running",
            "progress": 0.8
        }
    }


@app.post("/api/v1/resources/batch")
async def batch_operations(request: BatchRequest):
    result = await api_gateway.handle_batch(request)
    return {"code": 200, "data": result.model_dump()}


@app.post("/api/v1/adversarial/generate")
async def generate_adversarial(
    base_prompt: str,
    attack_type: AttackType = AttackType.PROMPT_INJECTION,
    iterations: int = 5
):
    config = AttackConfig(
        attack_type=attack_type,
        iterations=iterations
    )
    prompts = await adversarial_generator.generate(base_prompt, config)
    return {
        "code": 200,
        "data": {
            "count": len(prompts),
            "prompts": [p.model_dump() for p in prompts]
        }
    }


@app.post("/api/v1/adversarial/generate-all")
async def generate_all_adversarial(base_prompt: str, target_behavior: Optional[str] = None):
    prompts = await adversarial_generator.generate_all_strategies(base_prompt, target_behavior)
    return {
        "code": 200,
        "data": {
            "count": len(prompts),
            "strategies": len(adversarial_generator._strategies),
            "prompts": [p.model_dump() for p in prompts]
        }
    }


@app.post("/api/v1/adversarial/evaluate")
async def evaluate_adversarial(model_name: str = "test-model"):
    prompts = adversarial_generator.get_all_prompts()
    if not prompts:
        return {"code": 400, "error": "No prompts to evaluate. Generate some first."}
    
    report = await safety_evaluator.evaluate_batch(prompts, model_name)
    summary = safety_evaluator.get_vulnerability_summary(report)
    
    return {
        "code": 200,
        "data": {
            "report": report.model_dump(),
            "summary": summary
        }
    }


@app.get("/api/v1/adversarial/stats")
async def get_adversarial_stats():
    return {"code": 200, "data": adversarial_generator.get_statistics()}


@app.post("/api/v1/prompts")
async def create_prompt(request: PromptCreateRequest):
    prompt = prompt_manager.create_prompt(request)
    return {"code": 201, "data": prompt.model_dump()}


@app.get("/api/v1/prompts")
async def list_prompts(name: Optional[str] = None, status: Optional[str] = None):
    prompts = prompt_manager.list_prompts(
        name_filter=name,
        status_filter=status
    )
    return {"code": 200, "data": [p.model_dump() for p in prompts]}


@app.get("/api/v1/prompts/{version_id}")
async def get_prompt(version_id: str):
    prompt = prompt_manager.get_prompt(version_id)
    if not prompt:
        raise HTTPException(status_code=404, detail="Prompt not found")
    return {"code": 200, "data": prompt.model_dump()}


@app.post("/api/v1/prompts/{version_id}/variant")
async def create_prompt_variant(version_id: str, new_content: str, changes: str):
    variant = prompt_manager.create_variant(version_id, new_content, changes)
    if not variant:
        raise HTTPException(status_code=404, detail="Base prompt not found")
    return {"code": 201, "data": variant.model_dump()}


@app.post("/api/v1/experiments")
async def create_experiment(config: ExperimentConfig):
    experiment = experiment_manager.create_experiment(config)
    return {"code": 201, "data": experiment.model_dump()}


@app.post("/api/v1/experiments/{experiment_id}/start")
async def start_experiment(experiment_id: str):
    if not experiment_manager.start_experiment(experiment_id):
        raise HTTPException(status_code=404, detail="Experiment not found or already started")
    return {"code": 200}


@app.post("/api/v1/experiments/{experiment_id}/stop")
async def stop_experiment(experiment_id: str):
    if not experiment_manager.stop_experiment(experiment_id):
        raise HTTPException(status_code=404, detail="Experiment not found or not running")
    return {"code": 200}


@app.post("/api/v1/experiments/{experiment_id}/assign/{user_id}")
async def assign_variant(experiment_id: str, user_id: str):
    variant = experiment_manager.assign_variant(experiment_id, user_id)
    return {"code": 200, "data": {"variant_id": variant}}


@app.post("/api/v1/experiments/{experiment_id}/record")
async def record_experiment_metric(
    experiment_id: str,
    variant_id: str,
    metric_name: str,
    value: float
):
    experiment_manager.record_metric(experiment_id, variant_id, metric_name, value)
    return {"code": 200}


@app.get("/api/v1/experiments/{experiment_id}/result")
async def get_experiment_result(experiment_id: str):
    result = experiment_manager.get_experiment_result(experiment_id)
    if not result:
        raise HTTPException(status_code=404, detail="Experiment not found")
    return {"code": 200, "data": result.model_dump()}


@app.get("/api/v1/experiments")
async def list_experiments(status: Optional[str] = None):
    experiments = experiment_manager.list_experiments(status_filter=status)
    return {"code": 200, "data": [e.model_dump() for e in experiments]}


@app.post("/api/v1/dashboard/offline-metrics")
async def record_offline_metric(metric: OfflineMetric):
    dashboard.record_offline_metric(metric)
    return {"code": 201}


@app.post("/api/v1/dashboard/online-metrics/{metric_name}")
async def record_online_metric(metric_name: str, value: float):
    dashboard.record_online_metric(metric_name, value)
    return {"code": 200}


@app.get("/api/v1/dashboard/evaluation/{model_name}")
async def get_model_evaluation(model_name: str, version: Optional[str] = None):
    evaluation = dashboard.get_model_evaluation(model_name, version)
    if not evaluation:
        raise HTTPException(status_code=404, detail="Model evaluation not found")
    return {"code": 200, "data": evaluation.model_dump()}


@app.get("/api/v1/dashboard/snapshot")
async def get_dashboard_snapshot():
    snapshot = dashboard.get_dashboard_snapshot()
    return {"code": 200, "data": snapshot.model_dump()}


@app.post("/api/v1/dashboard/compare")
async def compare_models(model_a: str, model_b: str):
    comparison = dashboard.compare_models(model_a, model_b)
    return {"code": 200, "data": comparison.model_dump()}


@app.post("/api/v1/dashboard/drift-baseline/{metric_name}")
async def set_drift_baseline(metric_name: str, baseline_data: List[float]):
    dashboard.set_drift_baseline(metric_name, baseline_data)
    return {"code": 200}


@app.post("/api/v1/dashboard/alerts/{alert_id}/acknowledge")
async def acknowledge_alert(alert_id: str):
    if not dashboard.acknowledge_alert(alert_id):
        raise HTTPException(status_code=404, detail="Alert not found")
    return {"code": 200}


@app.post("/api/v1/gpu/tasks")
async def submit_gpu_task(request: TaskRequest):
    response = await gpu_scheduler.submit_task(request)
    return {"code": 201, "data": response.model_dump()}


@app.get("/api/v1/gpu/tasks/{task_id}")
async def get_gpu_task_status(task_id: str):
    status = gpu_scheduler.get_task_status(task_id)
    if not status:
        raise HTTPException(status_code=404, detail="Task not found")
    return {"code": 200, "data": status.model_dump()}


@app.post("/api/v1/gpu/tasks/{task_id}/cancel")
async def cancel_gpu_task(task_id: str):
    if not await gpu_scheduler.cancel_task(task_id):
        raise HTTPException(status_code=404, detail="Task not found or cannot be cancelled")
    return {"code": 200}


@app.get("/api/v1/gpu/status")
async def get_gpu_status():
    statuses = gpu_scheduler.get_gpu_status()
    return {"code": 200, "data": [s.model_dump() for s in statuses]}


@app.get("/api/v1/gpu/queue-stats")
async def get_gpu_queue_stats():
    stats = gpu_scheduler.get_queue_stats()
    return {"code": 200, "data": stats}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "app.main:app",
        host="0.0.0.0",
        port=settings.API_GATEWAY_PORT,
        reload=settings.DEBUG
    )
