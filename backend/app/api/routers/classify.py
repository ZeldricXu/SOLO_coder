from fastapi import APIRouter, HTTPException, Query
from typing import List, Optional
from pydantic import BaseModel
from datetime import datetime

from app.core.config import settings
from app.modules.model_manager import model_manager
from app.modules.sentiment_analyzer import sentiment_analyzer
from app.modules.keyword_extractor import keyword_extractor
from app.modules.result_storage import result_storage

router = APIRouter()


class ClassificationOptions(BaseModel):
    confidence_threshold: float = settings.DEFAULT_CONFIDENCE_THRESHOLD
    model_version: Optional[str] = None
    save_result: bool = True


class ClassificationRequest(BaseModel):
    text: str
    request_id: Optional[str] = None
    options: ClassificationOptions = ClassificationOptions()


class BatchClassificationRequest(BaseModel):
    texts: List[str]
    request_id: Optional[str] = None
    options: ClassificationOptions = ClassificationOptions()


class CategoryResult(BaseModel):
    label: str
    confidence: float


class SentimentResult(BaseModel):
    label: str
    confidence: float


class ClassificationResponse(BaseModel):
    categories: List[CategoryResult]
    sentiment: SentimentResult
    keywords: List[str]
    result_id: Optional[str] = None
    model_version: str
    confidence_threshold: float
    status: str
    message: str


class BatchClassificationResult(BaseModel):
    text: str
    categories: List[CategoryResult]
    sentiment: SentimentResult
    keywords: List[str]
    result_id: Optional[str] = None
    status: str
    message: str


class BatchClassificationResponse(BaseModel):
    results: List[BatchClassificationResult]
    processed_count: int
    success_count: int
    failed_count: int
    model_version: str
    confidence_threshold: float


class ResultListResponse(BaseModel):
    total_count: int
    limit: int
    offset: int
    results: List[dict]


class StatisticsResponse(BaseModel):
    total_count: int
    sentiment_distribution: dict
    category_distribution: dict
    model_version_distribution: dict


def _get_classifier():
    classifier = model_manager.get_current_classifier()
    if not classifier:
        model_manager.initialize_default_model()
        classifier = model_manager.get_current_classifier()
    return classifier


@router.post("/predict", response_model=ClassificationResponse)
async def predict(request: ClassificationRequest):
    try:
        if not request.text or not request.text.strip():
            raise HTTPException(status_code=400, detail="文本内容不能为空")

        classifier = _get_classifier()
        if not classifier:
            raise HTTPException(status_code=500, detail="分类模型未加载")

        confidence_threshold = request.options.confidence_threshold
        model_version = classifier.model_version

        classification_result = classifier.predict(
            text=request.text,
            confidence_threshold=confidence_threshold,
            model_version=model_version
        )

        if classification_result["status"] != "success":
            raise HTTPException(
                status_code=500,
                detail=f"分类失败: {classification_result['message']}"
            )

        categories = classification_result["categories"]

        sentiment_result = sentiment_analyzer.analyze(request.text)
        sentiment = {
            "label": sentiment_result.get("label", "neutral"),
            "confidence": sentiment_result.get("confidence", 0.5)
        }

        keyword_result = keyword_extractor.extract(request.text)
        keywords = keyword_result.get("keywords", [])

        result_id = None
        if request.options.save_result:
            save_result = result_storage.save_result(
                text=request.text,
                categories=categories,
                sentiment=sentiment,
                keywords=keywords,
                model_version=model_version,
                confidence_threshold=confidence_threshold,
                request_id=request.request_id
            )
            if save_result["success"]:
                result_id = save_result["result_id"]

        return ClassificationResponse(
            categories=[CategoryResult(label=c["label"], confidence=c["confidence"]) for c in categories],
            sentiment=SentimentResult(**sentiment),
            keywords=keywords,
            result_id=result_id,
            model_version=model_version,
            confidence_threshold=confidence_threshold,
            status="success",
            message="分类成功"
        )

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"分类服务异常: {str(e)}")


@router.post("/batch", response_model=BatchClassificationResponse)
async def batch_predict(request: BatchClassificationRequest):
    try:
        if not request.texts:
            raise HTTPException(status_code=400, detail="文本列表不能为空")

        classifier = _get_classifier()
        if not classifier:
            raise HTTPException(status_code=500, detail="分类模型未加载")

        confidence_threshold = request.options.confidence_threshold
        model_version = classifier.model_version

        results = []
        success_count = 0
        failed_count = 0

        for text in request.texts:
            try:
                if not text or not text.strip():
                    results.append(BatchClassificationResult(
                        text=text,
                        categories=[],
                        sentiment=SentimentResult(label="neutral", confidence=0.5),
                        keywords=[],
                        result_id=None,
                        status="error",
                        message="文本内容为空"
                    ))
                    failed_count += 1
                    continue

                classification_result = classifier.predict(
                    text=text,
                    confidence_threshold=confidence_threshold,
                    model_version=model_version
                )

                if classification_result["status"] != "success":
                    results.append(BatchClassificationResult(
                        text=text,
                        categories=[],
                        sentiment=SentimentResult(label="neutral", confidence=0.5),
                        keywords=[],
                        result_id=None,
                        status="error",
                        message=classification_result["message"]
                    ))
                    failed_count += 1
                    continue

                categories = classification_result["categories"]

                sentiment_result = sentiment_analyzer.analyze(text)
                sentiment = {
                    "label": sentiment_result.get("label", "neutral"),
                    "confidence": sentiment_result.get("confidence", 0.5)
                }

                keyword_result = keyword_extractor.extract(text)
                keywords = keyword_result.get("keywords", [])

                result_id = None
                if request.options.save_result:
                    save_result = result_storage.save_result(
                        text=text,
                        categories=categories,
                        sentiment=sentiment,
                        keywords=keywords,
                        model_version=model_version,
                        confidence_threshold=confidence_threshold,
                        request_id=request.request_id
                    )
                    if save_result["success"]:
                        result_id = save_result["result_id"]

                results.append(BatchClassificationResult(
                    text=text,
                    categories=[CategoryResult(label=c["label"], confidence=c["confidence"]) for c in categories],
                    sentiment=SentimentResult(**sentiment),
                    keywords=keywords,
                    result_id=result_id,
                    status="success",
                    message="分类成功"
                ))
                success_count += 1

            except Exception as e:
                results.append(BatchClassificationResult(
                    text=text,
                    categories=[],
                    sentiment=SentimentResult(label="neutral", confidence=0.5),
                    keywords=[],
                    result_id=None,
                    status="error",
                    message=str(e)
                ))
                failed_count += 1

        return BatchClassificationResponse(
            results=results,
            processed_count=len(request.texts),
            success_count=success_count,
            failed_count=failed_count,
            model_version=model_version,
            confidence_threshold=confidence_threshold
        )

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"批量分类服务异常: {str(e)}")


@router.get("/results/{result_id}")
async def get_result(result_id: str):
    try:
        result = result_storage.get_result(result_id)
        if not result:
            raise HTTPException(status_code=404, detail=f"结果不存在: {result_id}")
        return {"code": 200, "data": result}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"获取结果异常: {str(e)}")


@router.get("/results", response_model=ResultListResponse)
async def list_results(
    limit: int = Query(100, ge=1, le=1000),
    offset: int = Query(0, ge=0),
    model_version: Optional[str] = None
):
    try:
        result = result_storage.list_results(
            limit=limit,
            offset=offset,
            model_version=model_version
        )
        return ResultListResponse(
            total_count=result["total_count"],
            limit=result["limit"],
            offset=result["offset"],
            results=result["results"]
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"获取结果列表异常: {str(e)}")


@router.get("/statistics", response_model=StatisticsResponse)
async def get_statistics():
    try:
        stats = result_storage.get_statistics()
        return StatisticsResponse(
            total_count=stats["total_count"],
            sentiment_distribution=stats["sentiment_distribution"],
            category_distribution=stats["category_distribution"],
            model_version_distribution=stats["model_version_distribution"]
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"获取统计信息异常: {str(e)}")


@router.delete("/results/{result_id}")
async def delete_result(result_id: str):
    try:
        result = result_storage.delete_result(result_id)
        if result["success"]:
            return {"code": 200, "message": result["message"]}
        else:
            raise HTTPException(status_code=404, detail=result["message"])
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"删除结果异常: {str(e)}")
