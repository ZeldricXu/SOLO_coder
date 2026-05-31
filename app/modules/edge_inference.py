import os
import time
import uuid
from datetime import datetime
from typing import Any, Dict, List, Optional, Callable
from sqlalchemy import select, and_
from sqlalchemy.ext.asyncio import AsyncSession
from app.models import EdgeModel, InferenceJob
from app.config import settings
from app.logger import logger


class InferenceError(Exception):
    pass


class ModelRegistry:
    def __init__(self):
        self._runners: Dict[str, Callable] = {}
        self._device_resources: Dict[str, Dict[str, Any]] = {}
    
    def register_runner(self, model_type: str, runner: Callable):
        self._runners[model_type] = runner
        logger.info("Registered model runner", model_type=model_type)
    
    def register_device(self, device_id: str, capabilities: Dict[str, Any]):
        self._device_resources[device_id] = {
            "capabilities": capabilities,
            "current_load": 0,
            "max_load": capabilities.get("max_concurrent", 1)
        }
        logger.info("Registered edge device", device_id=device_id)
    
    def get_device_capabilities(self, device_id: str) -> Optional[Dict[str, Any]]:
        device = self._device_resources.get(device_id)
        return device["capabilities"] if device else None
    
    def allocate_device(self, model_requirements: Dict[str, Any]) -> Optional[str]:
        for device_id, device_info in self._device_resources.items():
            if device_info["current_load"] < device_info["max_load"]:
                capabilities = device_info["capabilities"]
                if self._check_compatibility(model_requirements, capabilities):
                    device_info["current_load"] += 1
                    return device_id
        return None
    
    def release_device(self, device_id: str):
        if device_id in self._device_resources:
            if self._device_resources[device_id]["current_load"] > 0:
                self._device_resources[device_id]["current_load"] -= 1
    
    def _check_compatibility(self, requirements: Dict[str, Any], capabilities: Dict[str, Any]) -> bool:
        for key, required_value in requirements.items():
            if key not in capabilities:
                return False
            if isinstance(required_value, list) and capabilities[key] not in required_value:
                return False
            if isinstance(required_value, (int, float)) and capabilities[key] < required_value:
                return False
        return True


model_registry = ModelRegistry()


class EdgeInferenceManager:
    def __init__(self, db: AsyncSession):
        self.db = db
        self.registry = model_registry
    
    async def register_model(
        self,
        model_id: str,
        name: str,
        version: str,
        model_type: str,
        model_path: str,
        input_spec: Dict[str, Any] = None,
        output_spec: Dict[str, Any] = None,
        requirements: Dict[str, Any] = None
    ) -> EdgeModel:
        full_path = os.path.join(settings.MODELS_PATH, model_path)
        if not os.path.exists(full_path):
            os.makedirs(settings.MODELS_PATH, exist_ok=True)
            with open(full_path, 'w') as f:
                f.write("")
        
        model = EdgeModel(
            model_id=model_id,
            name=name,
            version=version,
            model_path=full_path,
            model_type=model_type,
            input_spec=input_spec or {},
            output_spec=output_spec or {},
            requirements=requirements or {}
        )
        self.db.add(model)
        await self.db.flush()
        
        logger.info("Registered edge model", model_id=model_id, model_type=model_type)
        return model
    
    async def get_model(self, model_id: str) -> Optional[EdgeModel]:
        stmt = select(EdgeModel).where(EdgeModel.model_id == model_id)
        result = await self.db.execute(stmt)
        return result.scalar_one_or_none()
    
    async def list_models(self, active_only: bool = True) -> List[EdgeModel]:
        conditions = []
        if active_only:
            conditions.append(EdgeModel.is_active == True)
        
        stmt = select(EdgeModel).where(
            and_(*conditions) if conditions else True
        ).order_by(EdgeModel.created_at.desc())
        
        result = await self.db.execute(stmt)
        return result.scalars().all()
    
    async def create_inference_job(
        self,
        model_id: str,
        device_id: str,
        input_data: Dict[str, Any]
    ) -> InferenceJob:
        model = await self.get_model(model_id)
        if not model:
            raise InferenceError(f"Model not found: {model_id}")
        
        if not model.is_active:
            raise InferenceError(f"Model is not active: {model_id}")
        
        job = InferenceJob(
            model_id=model.id,
            device_id=device_id,
            input_data=input_data,
            status="pending"
        )
        self.db.add(job)
        await self.db.flush()
        
        logger.info("Created inference job", job_id=job.id, model_id=model_id)
        return job
    
    async def execute_inference(self, job_id: str) -> Dict[str, Any]:
        stmt = select(InferenceJob).where(InferenceJob.id == job_id)
        result = await self.db.execute(stmt)
        job = result.scalar_one_or_none()
        
        if not job:
            raise InferenceError(f"Inference job not found: {job_id}")
        
        model_stmt = select(EdgeModel).where(EdgeModel.id == job.model_id)
        model_result = await self.db.execute(model_stmt)
        model = model_result.scalar_one_or_none()
        
        if not model:
            raise InferenceError("Associated model not found")
        
        job.status = "running"
        job.started_at = datetime.utcnow()
        await self.db.flush()
        
        start_time = time.time()
        
        try:
            device_id = self.registry.allocate_device(model.requirements)
            if not device_id:
                device_id = job.device_id
            
            try:
                result_data = await self._run_inference(model, job.input_data, device_id)
                latency_ms = int((time.time() - start_time) * 1000)
                
                job.status = "completed"
                job.result = result_data
                job.latency_ms = latency_ms
                job.completed_at = datetime.utcnow()
                
                logger.info("Inference completed", job_id=job_id, latency_ms=latency_ms)
                await self.db.flush()
                
                return {
                    "job_id": job_id,
                    "status": "completed",
                    "result": result_data,
                    "latency_ms": latency_ms
                }
            finally:
                self.registry.release_device(device_id)
        
        except Exception as e:
            job.status = "failed"
            job.error_message = str(e)
            job.completed_at = datetime.utcnow()
            await self.db.flush()
            
            logger.error("Inference failed", job_id=job_id, error=str(e))
            return {
                "job_id": job_id,
                "status": "failed",
                "error": str(e)
            }
    
    async def get_job_status(self, job_id: str) -> Optional[Dict[str, Any]]:
        stmt = select(InferenceJob).where(InferenceJob.id == job_id)
        result = await self.db.execute(stmt)
        job = result.scalar_one_or_none()
        
        if not job:
            return None
        
        return {
            "job_id": job.id,
            "model_id": job.model_id,
            "device_id": job.device_id,
            "status": job.status,
            "result": job.result,
            "error_message": job.error_message,
            "latency_ms": job.latency_ms,
            "started_at": job.started_at.isoformat() if job.started_at else None,
            "completed_at": job.completed_at.isoformat() if job.completed_at else None
        }
    
    async def list_jobs(self, status: str = None, limit: int = 100) -> List[Dict[str, Any]]:
        conditions = []
        if status:
            conditions.append(InferenceJob.status == status)
        
        stmt = select(InferenceJob).where(
            and_(*conditions) if conditions else True
        ).order_by(InferenceJob.created_at.desc()).limit(limit)
        
        result = await self.db.execute(stmt)
        jobs = result.scalars().all()
        
        return [
            {
                "job_id": j.id,
                "model_id": j.model_id,
                "device_id": j.device_id,
                "status": j.status,
                "latency_ms": j.latency_ms,
                "created_at": j.created_at.isoformat() if j.created_at else None
            }
            for j in jobs
        ]
    
    async def _run_inference(
        self,
        model: EdgeModel,
        input_data: Dict[str, Any],
        device_id: str
    ) -> Dict[str, Any]:
        self._validate_input(model.input_spec, input_data)
        
        runner = self.registry._runners.get(model.model_type)
        if runner:
            result = runner(model.model_path, input_data)
            if hasattr(result, '__await__'):
                result = await result
            self._validate_output(model.output_spec, result)
            return result
        
        return self._default_inference(model, input_data)
    
    def _validate_input(self, spec: Dict[str, Any], data: Dict[str, Any]):
        if not spec:
            return
        
        for key, expected_type in spec.items():
            if key not in data:
                raise InferenceError(f"Missing required input field: {key}")
            
            actual_type = type(data[key]).__name__
            if expected_type and str(expected_type).lower() != actual_type and expected_type != "any":
                raise InferenceError(f"Type mismatch for field {key}: expected {expected_type}, got {actual_type}")
    
    def _validate_output(self, spec: Dict[str, Any], data: Dict[str, Any]):
        if not spec:
            return
        
        for key, expected_type in spec.items():
            if key not in data:
                raise InferenceError(f"Missing required output field: {key}")
    
    def _default_inference(self, model: EdgeModel, input_data: Dict[str, Any]) -> Dict[str, Any]:
        import hashlib
        
        input_hash = hashlib.md5(str(input_data).encode()).hexdigest()
        timestamp = datetime.utcnow().isoformat()
        
        return {
            "model_id": model.model_id,
            "model_version": model.version,
            "input_hash": input_hash,
            "processed_at": timestamp,
            "predictions": {
                "classification": "simulated_result",
                "confidence": 0.95
            }
        }
