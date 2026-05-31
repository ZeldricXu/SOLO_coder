from datetime import datetime
from typing import Any, Dict, List, Optional, Callable
from dataclasses import dataclass, field
from enum import Enum
import asyncio
import uuid
import time

from app.core.logger import logger
from app.core.context import RequestContext, init_context, cleanup_context, get_current_context
from app.core.events import event_bus, EventType, Event, build_event, on
from app.core.models import (
    ResourceRequest, ResourceResponse, StatusResponse,
    BatchOperation, BatchResponse, APIResponse, RunInstance,
    PhaseStatus, ResourceStatus
)


class ProcessingPhase(str, Enum):
    INIT = "init"
    VALIDATION = "validation"
    CONFIG_LOAD = "config_load"
    RESOURCE_ACQUIRE = "resource_acquire"
    CORE_PROCESS = "core_process"
    PERSIST = "persist"
    EMIT_EVENT = "emit_event"
    RESPONSE = "response"
    ERROR = "error"
    ROLLBACK = "rollback"
    CLEANUP = "cleanup"


class ValidationError(Exception):
    def __init__(self, message: str, details: Optional[Dict[str, Any]] = None):
        super().__init__(message)
        self.details = details or {}


class TimeoutError(Exception):
    def __init__(self, message: str = "上游服务响应超时"):
        super().__init__(message)


class ResourcePool:
    def __init__(self, max_size: int = 10):
        self._max_size = max_size
        self._available = asyncio.Queue(maxsize=max_size)
        self._acquired = 0
        self._lock = asyncio.Lock()

    async def acquire(self) -> bool:
        async with self._lock:
            if self._acquired < self._max_size:
                self._acquired += 1
                return True
        return False

    async def release(self):
        async with self._lock:
            if self._acquired > 0:
                self._acquired -= 1

    @property
    def available(self) -> int:
        return self._max_size - self._acquired

    @property
    def acquired(self) -> int:
        return self._acquired


class RequestHandler:
    def __init__(self):
        self._validators: Dict[str, Callable] = {}
        self._config_loaders: Dict[str, Callable] = {}
        self._processors: Dict[str, Callable] = {}
        self._rollback_handlers: Dict[str, Callable] = {}
        self._resource_pool = ResourcePool(max_size=100)

    def register_validator(self, op_type: str, validator: Callable):
        self._validators[op_type] = validator

    def register_config_loader(self, namespace: str, loader: Callable):
        self._config_loaders[namespace] = loader

    def register_processor(self, op_type: str, processor: Callable):
        self._processors[op_type] = processor

    def register_rollback_handler(self, op_type: str, handler: Callable):
        self._rollback_handlers[op_type] = handler

    def validate_params(self, params: Dict[str, Any], required: Optional[List[str]] = None) -> bool:
        if not params:
            raise ValidationError("参数不能为空")

        if required:
            missing = [f for f in required if f not in params]
            if missing:
                raise ValidationError(
                    f"缺少必填参数: {', '.join(missing)}",
                    details={"missing_fields": missing}
                )
        return True

    async def load_config(self, namespace: str) -> Dict[str, Any]:
        if namespace in self._config_loaders:
            return await self._config_loaders[namespace]()
        return {"timeout": 30, "retries": 3, "poolSize": 10, "rules": {}}

    async def acquire_resource(self, pool_size: int = 1) -> bool:
        for _ in range(pool_size):
            if not await self._resource_pool.acquire():
                return False
        return True

    async def release_resource(self):
        await self._resource_pool.release()

    async def process_core(self, op_type: str, payload: Dict[str, Any],
                           rules: Dict[str, Any]) -> Any:
        if op_type in self._processors:
            return await self._processors[op_type](payload, rules)
        return {"processed": True, "timestamp": datetime.utcnow().isoformat()}

    async def persist_result(self, result: Any) -> bool:
        try:
            logger.info(f"Persisting result: {type(result).__name__}")
            return True
        except Exception as e:
            logger.error(f"Failed to persist result: {e}")
            raise

    async def rollback_transaction(self, context: RequestContext, op_type: str):
        logger.warning(f"Initiating rollback for operation: {op_type}")
        if op_type in self._rollback_handlers:
            try:
                await self._rollback_handlers[op_type](context)
                logger.info("Rollback completed successfully")
            except Exception as e:
                logger.error(f"Rollback failed: {e}")

    def record_metrics(self, context: RequestContext):
        duration = (datetime.utcnow() - context.start_time).total_seconds() * 1000
        context.record_metric("duration_ms", duration)
        context.record_metric("error_count", len(context.errors))
        logger.info(f"Request {context.trace_id} metrics: {context.metrics}")


class ResponseGenerator:
    def success_response(self, data: Any, code: int = 200, message: str = "success") -> APIResponse:
        return APIResponse(code=code, data=data, message=message)

    def error_response(self, code: int, message: str, details: Optional[Dict[str, Any]] = None) -> APIResponse:
        return APIResponse(
            code=code,
            data=details,
            message=message
        )

    def resource_created(self, resource_id: str, status: str = ResourceStatus.PROVISIONING) -> APIResponse:
        return APIResponse(
            code=201,
            data=ResourceResponse(id=resource_id, status=status)
        )

    def status_response(self, resource_id: str, status: str, progress: float = 0.0) -> APIResponse:
        return APIResponse(
            code=200,
            data=StatusResponse(id=resource_id, status=status, progress=progress)
        )

    def batch_response(self, results: List[Dict[str, Any]]) -> APIResponse:
        return APIResponse(
            code=200,
            data=BatchResponse(results=results)
        )


class CoreProcessor:
    def __init__(self):
        self._request_handler = RequestHandler()
        self._response_generator = ResponseGenerator()
        self._run_instances: Dict[str, RunInstance] = {}
        self._resources: Dict[str, Dict[str, Any]] = {}
        logger.info("CoreProcessor initialized")

    @property
    def request_handler(self) -> RequestHandler:
        return self._request_handler

    @property
    def response_generator(self) -> ResponseGenerator:
        return self._response_generator

    def _build_request(self, request_data: Dict[str, Any]) -> Dict[str, Any]:
        return {
            "traceId": request_data.get("trace_id", uuid.uuid4().hex),
            "params": request_data.get("params", {}),
            "namespace": request_data.get("namespace", "default"),
            "payload": request_data.get("payload", {}),
            "op_type": request_data.get("op_type", "default")
        }

    async def execute_handler(self, request: Dict[str, Any]) -> APIResponse:
        ctx = init_context(request.get("traceId"))
        start_time = time.time()

        try:
            ctx.record_metric("phase", ProcessingPhase.VALIDATION)
            self._request_handler.validate_params(request.get("params", {}))

            ctx.record_metric("phase", ProcessingPhase.CONFIG_LOAD)
            config = await self._request_handler.load_config(request.get("namespace", "default"))

            ctx.record_metric("phase", ProcessingPhase.RESOURCE_ACQUIRE)
            if not await self._request_handler.acquire_resource(config.get("poolSize", 1)):
                return self._response_generator.error_response(503, "资源暂时不可用")

            try:
                ctx.record_metric("phase", ProcessingPhase.CORE_PROCESS)
                result = await self._request_handler.process_core(
                    request.get("op_type", "default"),
                    request.get("payload", {}),
                    config.get("rules", {})
                )

                ctx.record_metric("phase", ProcessingPhase.PERSIST)
                await self._request_handler.persist_result(result)

                ctx.record_metric("phase", ProcessingPhase.EMIT_EVENT)
                await event_bus.emit_async(build_event(EventType.TASK_COMPLETED, result))

                ctx.record_metric("phase", ProcessingPhase.RESPONSE)
                ctx.record_metric("success", True)
                return self._response_generator.success_response(result)

            finally:
                await self._request_handler.release_resource()

        except ValidationError as e:
            ctx.add_error(e)
            ctx.record_metric("phase", ProcessingPhase.ERROR)
            logger.error(f"Validation error: {e}")
            return self._response_generator.error_response(422, str(e), e.details)

        except TimeoutError as e:
            ctx.add_error(e)
            ctx.record_metric("phase", ProcessingPhase.ERROR)
            logger.error(f"Timeout error: {e}")
            return self._response_generator.error_response(504, str(e))

        except Exception as e:
            ctx.add_error(e)
            ctx.record_metric("phase", ProcessingPhase.ROLLBACK)
            logger.exception(f"Unexpected error in handler: {e}")
            await self._request_handler.rollback_transaction(ctx, request.get("op_type", "default"))
            return self._response_generator.error_response(500, "内部处理错误")

        finally:
            ctx.record_metric("phase", ProcessingPhase.CLEANUP)
            ctx.record_metric("total_duration_ms", (time.time() - start_time) * 1000)
            self._request_handler.record_metrics(ctx)
            cleanup_context()

    def create_resource(self, request: ResourceRequest) -> APIResponse:
        resource_id = f"rsc_{uuid.uuid4().hex[:6]}"
        self._resources[resource_id] = {
            "type": request.type,
            "config": request.config,
            "labels": request.labels,
            "status": ResourceStatus.PROVISIONING,
            "created_at": datetime.utcnow()
        }

        run_instance = RunInstance(
            entity_id=resource_id,
            phase=PhaseStatus.PENDING,
            progress=0.0
        )
        self._run_instances[run_instance.run_id] = run_instance

        event_bus.emit(build_event(EventType.TASK_STARTED, {
            "resource_id": resource_id,
            "run_id": run_instance.run_id
        }))

        return self._response_generator.resource_created(resource_id)

    def get_resource_status(self, resource_id: str) -> APIResponse:
        resource = self._resources.get(resource_id)
        if not resource:
            return self._response_generator.error_response(404, "资源不存在")

        return self._response_generator.status_response(
            resource_id,
            resource.get("status", ResourceStatus.RUNNING),
            resource.get("progress", 0.0)
        )

    async def execute_batch(self, operations: List[BatchOperation]) -> APIResponse:
        results = []
        for op in operations:
            result = await self._execute_single_operation(op)
            results.append(result)

        return self._response_generator.batch_response(results)

    async def _execute_single_operation(self, op: BatchOperation) -> Dict[str, Any]:
        try:
            if op.action == "start":
                resource = self._resources.get(op.id)
                if resource:
                    resource["status"] = ResourceStatus.RUNNING
                    resource["progress"] = 0.5
                    return {"id": op.id, "action": op.action, "status": "success"}
                return {"id": op.id, "action": op.action, "status": "failed", "error": "资源不存在"}
            elif op.action == "pause":
                resource = self._resources.get(op.id)
                if resource:
                    resource["status"] = ResourceStatus.PAUSED
                    return {"id": op.id, "action": op.action, "status": "success"}
                return {"id": op.id, "action": op.action, "status": "failed", "error": "资源不存在"}
            elif op.action == "stop":
                resource = self._resources.get(op.id)
                if resource:
                    resource["status"] = ResourceStatus.COMPLETED
                    resource["progress"] = 1.0
                    return {"id": op.id, "action": op.action, "status": "success"}
                return {"id": op.id, "action": op.action, "status": "failed", "error": "资源不存在"}
            else:
                return {"id": op.id, "action": op.action, "status": "failed", "error": "未知操作"}
        except Exception as e:
            return {"id": op.id, "action": op.action, "status": "failed", "error": str(e)}

    def update_run_instance(self, run_id: str, phase: PhaseStatus,
                            progress: float, error_detail: Optional[str] = None):
        instance = self._run_instances.get(run_id)
        if instance:
            instance.phase = phase
            instance.progress = progress
            if error_detail:
                instance.error_detail = error_detail
            if phase in [PhaseStatus.COMPLETED, PhaseStatus.FAILED]:
                instance.completed_at = datetime.utcnow()

    def get_run_instance(self, run_id: str) -> Optional[RunInstance]:
        return self._run_instances.get(run_id)


core_processor = CoreProcessor()


@on(EventType.TASK_STARTED)
def on_task_started(event: Event):
    logger.info(f"Task started: {event.payload}")


@on(EventType.TASK_COMPLETED)
def on_task_completed(event: Event):
    logger.info(f"Task completed: {event.payload}")
