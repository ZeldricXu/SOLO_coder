from fastapi import APIRouter, HTTPException
from typing import List, Optional
from pydantic import BaseModel
from datetime import datetime

from app.modules.model_manager import model_manager
from app.modules.training_service import training_service

router = APIRouter()


class ModelInfo(BaseModel):
    model_id: str
    model_type: str
    version: str
    labels: List[str]
    training_samples: int
    accuracy: float
    precision: float
    recall: float
    f1_score: float
    model_path: str
    vectorizer_path: str
    is_active: bool
    created_at: Optional[str]
    updated_at: Optional[str]
    description: Optional[str]


class ModelListResponse(BaseModel):
    models: List[ModelInfo]
    total_count: int


class TrainingDataItem(BaseModel):
    text: str
    labels: List[str]


class TrainRequest(BaseModel):
    training_data: List[TrainingDataItem]
    model_type: str = "multilabel_classifier"
    test_size: float = 0.2
    random_state: int = 42
    auto_activate: bool = False
    description: Optional[str] = None


class TrainResponse(BaseModel):
    success: bool
    message: str
    job_id: str
    model_info: Optional[ModelInfo]
    metrics: Optional[dict]


class JobInfo(BaseModel):
    job_id: str
    model_type: str
    status: str
    training_samples: int
    test_size: float
    random_state: int
    result_model_id: Optional[str]
    error_message: Optional[str]
    started_at: Optional[str]
    completed_at: Optional[str]
    created_at: Optional[str]
    updated_at: Optional[str]


class JobListResponse(BaseModel):
    jobs: List[JobInfo]
    total_count: int


class SwitchModelRequest(BaseModel):
    model_id: str


class SwitchModelResponse(BaseModel):
    success: bool
    message: str
    model_info: Optional[ModelInfo]


@router.get("/list", response_model=ModelListResponse)
async def list_models():
    try:
        models = model_manager.list_models()
        model_infos = []
        for m in models:
            model_infos.append(ModelInfo(
                model_id=m["model_id"],
                model_type=m["model_type"],
                version=m["version"],
                labels=m["labels"],
                training_samples=m["training_samples"],
                accuracy=m["accuracy"],
                precision=m["precision"],
                recall=m["recall"],
                f1_score=m["f1_score"],
                model_path=m["model_path"],
                vectorizer_path=m["vectorizer_path"],
                is_active=m["is_active"],
                created_at=m["created_at"],
                updated_at=m["updated_at"],
                description=m["description"]
            ))
        return ModelListResponse(
            models=model_infos,
            total_count=len(models)
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"获取模型列表异常: {str(e)}")


@router.get("/active", response_model=ModelInfo)
async def get_active_model():
    try:
        model = model_manager.get_active_model()
        if not model:
            raise HTTPException(status_code=404, detail="没有激活的模型")
        return ModelInfo(
            model_id=model["model_id"],
            model_type=model["model_type"],
            version=model["version"],
            labels=model["labels"],
            training_samples=model["training_samples"],
            accuracy=model["accuracy"],
            precision=model["precision"],
            recall=model["recall"],
            f1_score=model["f1_score"],
            model_path=model["model_path"],
            vectorizer_path=model["vectorizer_path"],
            is_active=model["is_active"],
            created_at=model["created_at"],
            updated_at=model["updated_at"],
            description=model["description"]
        )
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"获取激活模型异常: {str(e)}")


@router.get("/{model_id}", response_model=ModelInfo)
async def get_model(model_id: str):
    try:
        model = model_manager.get_model_info(model_id)
        if not model:
            raise HTTPException(status_code=404, detail=f"模型不存在: {model_id}")
        return ModelInfo(
            model_id=model["model_id"],
            model_type=model["model_type"],
            version=model["version"],
            labels=model["labels"],
            training_samples=model["training_samples"],
            accuracy=model["accuracy"],
            precision=model["precision"],
            recall=model["recall"],
            f1_score=model["f1_score"],
            model_path=model["model_path"],
            vectorizer_path=model["vectorizer_path"],
            is_active=model["is_active"],
            created_at=model["created_at"],
            updated_at=model["updated_at"],
            description=model["description"]
        )
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"获取模型信息异常: {str(e)}")


@router.post("/switch", response_model=SwitchModelResponse)
async def switch_model(request: SwitchModelRequest):
    try:
        result = model_manager.switch_model(request.model_id)
        if result["success"]:
            model_info = None
            if result.get("model_info"):
                m = result["model_info"]
                model_info = ModelInfo(
                    model_id=m["model_id"],
                    model_type=m["model_type"],
                    version=m["version"],
                    labels=m["labels"],
                    training_samples=m["training_samples"],
                    accuracy=m["accuracy"],
                    precision=m["precision"],
                    recall=m["recall"],
                    f1_score=m["f1_score"],
                    model_path=m["model_path"],
                    vectorizer_path=m["vectorizer_path"],
                    is_active=m["is_active"],
                    created_at=m["created_at"],
                    updated_at=m["updated_at"],
                    description=m["description"]
                )
            return SwitchModelResponse(
                success=True,
                message=result["message"],
                model_info=model_info
            )
        else:
            raise HTTPException(status_code=400, detail=result["message"])
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"切换模型异常: {str(e)}")


@router.post("/train", response_model=TrainResponse)
async def train_model(request: TrainRequest):
    try:
        if not request.training_data:
            raise HTTPException(status_code=400, detail="训练数据不能为空")

        training_data = [item.model_dump() for item in request.training_data]

        result = training_service.start_training(
            training_data=training_data,
            model_type=request.model_type,
            test_size=request.test_size,
            random_state=request.random_state,
            auto_activate=request.auto_activate,
            description=request.description
        )

        if result["success"]:
            model_info = None
            if result.get("model_info"):
                m = result["model_info"]
                model_info = ModelInfo(
                    model_id=m["model_id"],
                    model_type=m["model_type"],
                    version=m["version"],
                    labels=m["labels"],
                    training_samples=m["training_samples"],
                    accuracy=m["accuracy"],
                    precision=m["precision"],
                    recall=m["recall"],
                    f1_score=m["f1_score"],
                    model_path=m["model_path"],
                    vectorizer_path=m["vectorizer_path"],
                    is_active=m["is_active"],
                    created_at=m["created_at"],
                    updated_at=m["updated_at"],
                    description=m["description"]
                )
            return TrainResponse(
                success=True,
                message=result["message"],
                job_id=result["job_id"],
                model_info=model_info,
                metrics=result.get("metrics")
            )
        else:
            raise HTTPException(status_code=400, detail=result["message"])

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"训练模型异常: {str(e)}")


@router.get("/jobs", response_model=JobListResponse)
async def list_jobs(limit: int = 100):
    try:
        jobs = training_service.list_training_jobs(limit=limit)
        job_infos = []
        for j in jobs:
            job_infos.append(JobInfo(
                job_id=j["job_id"],
                model_type=j["model_type"],
                status=j["status"],
                training_samples=j["training_samples"],
                test_size=j["test_size"],
                random_state=j["random_state"],
                result_model_id=j["result_model_id"],
                error_message=j["error_message"],
                started_at=j["started_at"],
                completed_at=j["completed_at"],
                created_at=j["created_at"],
                updated_at=j["updated_at"]
            ))
        return JobListResponse(
            jobs=job_infos,
            total_count=len(jobs)
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"获取训练任务列表异常: {str(e)}")


@router.get("/jobs/{job_id}", response_model=JobInfo)
async def get_job(job_id: str):
    try:
        job = training_service.get_training_job(job_id)
        if not job:
            raise HTTPException(status_code=404, detail=f"训练任务不存在: {job_id}")
        return JobInfo(
            job_id=job["job_id"],
            model_type=job["model_type"],
            status=job["status"],
            training_samples=job["training_samples"],
            test_size=job["test_size"],
            random_state=job["random_state"],
            result_model_id=job["result_model_id"],
            error_message=job["error_message"],
            started_at=job["started_at"],
            completed_at=job["completed_at"],
            created_at=job["created_at"],
            updated_at=job["updated_at"]
        )
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"获取训练任务异常: {str(e)}")


@router.delete("/{model_id}")
async def delete_model(model_id: str):
    try:
        result = model_manager.delete_model(model_id)
        if result["success"]:
            return {"code": 200, "message": result["message"]}
        else:
            raise HTTPException(status_code=400, detail=result["message"])
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"删除模型异常: {str(e)}")
