import uuid
from datetime import datetime
from typing import List, Dict, Optional
from sqlalchemy.orm import Session
from sqlalchemy import func, desc

from app.core.config import settings
from app.core.database import SessionLocal
from app.core.models import ClassificationResult as DBClassificationResult


class ResultStorage:
    def __init__(self):
        pass

    def _get_db(self) -> Session:
        return SessionLocal()

    def _generate_result_id(self) -> str:
        return f"result_{datetime.now().strftime('%Y%m%d%H%M%S')}_{uuid.uuid4().hex[:8]}"

    def save_result(
        self,
        text: str,
        categories: List[Dict],
        sentiment: Dict,
        keywords: List[str],
        model_version: str,
        confidence_threshold: float,
        request_id: Optional[str] = None
    ) -> Dict:
        db = self._get_db()
        try:
            result_id = self._generate_result_id()

            result = DBClassificationResult(
                result_id=result_id,
                request_id=request_id,
                text=text,
                categories=categories,
                sentiment=sentiment,
                keywords=keywords,
                model_version=model_version,
                confidence_threshold=confidence_threshold
            )
            db.add(result)
            db.commit()
            db.refresh(result)

            return {
                "success": True,
                "message": "结果保存成功",
                "result_id": result_id,
                "result": self._result_to_dict(result)
            }

        except Exception as e:
            db.rollback()
            return {
                "success": False,
                "message": f"结果保存失败: {str(e)}"
            }
        finally:
            db.close()

    def save_batch_results(
        self,
        results: List[Dict],
        model_version: str,
        confidence_threshold: float
    ) -> Dict:
        db = self._get_db()
        try:
            saved_results = []
            failed_results = []

            for i, item in enumerate(results):
                try:
                    result_id = self._generate_result_id()

                    result = DBClassificationResult(
                        result_id=result_id,
                        request_id=item.get("request_id"),
                        text=item.get("text", ""),
                        categories=item.get("categories", []),
                        sentiment=item.get("sentiment", {"label": "neutral", "confidence": 0.5}),
                        keywords=item.get("keywords", []),
                        model_version=model_version,
                        confidence_threshold=confidence_threshold
                    )
                    db.add(result)
                    db.commit()
                    db.refresh(result)

                    saved_results.append({
                        "index": i,
                        "result_id": result_id,
                        "success": True
                    })

                except Exception as e:
                    db.rollback()
                    failed_results.append({
                        "index": i,
                        "error": str(e),
                        "success": False
                    })

            return {
                "success": True,
                "message": f"批量保存完成，成功: {len(saved_results)}, 失败: {len(failed_results)}",
                "total_count": len(results),
                "saved_count": len(saved_results),
                "failed_count": len(failed_results),
                "saved_results": saved_results,
                "failed_results": failed_results
            }

        finally:
            db.close()

    def get_result(self, result_id: str) -> Optional[Dict]:
        db = self._get_db()
        try:
            result = db.query(DBClassificationResult).filter(
                DBClassificationResult.result_id == result_id
            ).first()
            if result:
                return self._result_to_dict(result)
            return None
        finally:
            db.close()

    def get_results_by_request(self, request_id: str) -> List[Dict]:
        db = self._get_db()
        try:
            results = db.query(DBClassificationResult).filter(
                DBClassificationResult.request_id == request_id
            ).order_by(desc(DBClassificationResult.classified_at)).all()
            return [self._result_to_dict(r) for r in results]
        finally:
            db.close()

    def list_results(
        self,
        limit: int = 100,
        offset: int = 0,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None,
        model_version: Optional[str] = None
    ) -> Dict:
        db = self._get_db()
        try:
            query = db.query(DBClassificationResult)

            if start_time:
                query = query.filter(DBClassificationResult.classified_at >= start_time)
            if end_time:
                query = query.filter(DBClassificationResult.classified_at <= end_time)
            if model_version:
                query = query.filter(DBClassificationResult.model_version == model_version)

            total_count = query.count()

            results = query.order_by(
                desc(DBClassificationResult.classified_at)
            ).offset(offset).limit(limit).all()

            return {
                "total_count": total_count,
                "limit": limit,
                "offset": offset,
                "results": [self._result_to_dict(r) for r in results]
            }
        finally:
            db.close()

    def delete_result(self, result_id: str) -> Dict:
        db = self._get_db()
        try:
            result = db.query(DBClassificationResult).filter(
                DBClassificationResult.result_id == result_id
            ).first()
            if not result:
                return {
                    "success": False,
                    "message": f"结果不存在: {result_id}"
                }

            db.delete(result)
            db.commit()

            return {
                "success": True,
                "message": f"结果删除成功: {result_id}"
            }

        except Exception as e:
            db.rollback()
            return {
                "success": False,
                "message": f"结果删除失败: {str(e)}"
            }
        finally:
            db.close()

    def get_statistics(
        self,
        start_time: Optional[datetime] = None,
        end_time: Optional[datetime] = None
    ) -> Dict:
        db = self._get_db()
        try:
            query = db.query(DBClassificationResult)

            if start_time:
                query = query.filter(DBClassificationResult.classified_at >= start_time)
            if end_time:
                query = query.filter(DBClassificationResult.classified_at <= end_time)

            total_count = query.count()

            all_sentiments = []
            all_categories = []

            results = query.all()
            for r in results:
                if r.sentiment and isinstance(r.sentiment, dict):
                    all_sentiments.append(r.sentiment.get("label", "neutral"))
                if r.categories and isinstance(r.categories, list):
                    for cat in r.categories:
                        if isinstance(cat, dict):
                            all_categories.append(cat.get("label"))

            sentiment_counts = {}
            for s in all_sentiments:
                sentiment_counts[s] = sentiment_counts.get(s, 0) + 1

            category_counts = {}
            for c in all_categories:
                if c:
                    category_counts[c] = category_counts.get(c, 0) + 1

            model_versions = db.query(
                DBClassificationResult.model_version,
                func.count(DBClassificationResult.id)
            ).group_by(DBClassificationResult.model_version).all()

            model_version_counts = {mv[0]: mv[1] for mv in model_versions}

            return {
                "total_count": total_count,
                "sentiment_distribution": sentiment_counts,
                "category_distribution": category_counts,
                "model_version_distribution": model_version_counts
            }

        finally:
            db.close()

    def _result_to_dict(self, result: DBClassificationResult) -> Dict:
        return {
            "result_id": result.result_id,
            "request_id": result.request_id,
            "text": result.text,
            "categories": result.categories,
            "sentiment": result.sentiment,
            "keywords": result.keywords,
            "model_version": result.model_version,
            "confidence_threshold": result.confidence_threshold,
            "classified_at": result.classified_at.isoformat() if result.classified_at else None,
            "created_at": result.created_at.isoformat() if result.created_at else None,
            "updated_at": result.updated_at.isoformat() if result.updated_at else None
        }


result_storage = ResultStorage()
