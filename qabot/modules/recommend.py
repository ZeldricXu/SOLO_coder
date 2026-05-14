from typing import List, Optional, Dict, Any
from qabot.models import Database, Knowledge, RecommendItem
from qabot.config import settings
from qabot.modules.queue import RecommendationQueueManager, queue_manager


class RecommendModule:
    def __init__(self, db: Database):
        self.db = db
        self._setup_worker()
    
    def _setup_worker(self):
        def recommend_worker_func(matched_knowledge_id: Optional[str], qa_id: str):
            matched_knowledge = None
            if matched_knowledge_id:
                matched_knowledge = self.db.get_knowledge(matched_knowledge_id)
            
            recommendations = self._do_generate_recommendations(
                matched_knowledge=matched_knowledge,
                qa_id=qa_id
            )
            
            return [r.model_dump() for r in recommendations]
        
        if not queue_manager._recommend_func:
            queue_manager.set_recommend_func(recommend_worker_func)
    
    def start_worker(self):
        queue_manager.start_worker()
    
    def stop_worker(self):
        queue_manager.stop_worker()
    
    def _knowledge_to_item(self, knowledge: Knowledge, score: float, recommend_type: str) -> RecommendItem:
        return RecommendItem(
            knowledge_id=knowledge.knowledge_id,
            knowledge_title=knowledge.knowledge_title,
            knowledge_category=knowledge.knowledge_category,
            score=score,
            recommend_type=recommend_type
        )
    
    def _get_related_recommendations(self, matched_knowledge: Knowledge) -> List[RecommendItem]:
        recommendations = []
        
        for related_id in matched_knowledge.related_knowledge:
            related_knowledge = self.db.get_knowledge(related_id)
            if related_knowledge:
                recommendations.append(
                    self._knowledge_to_item(related_knowledge, 0.9, "related")
                )
        
        if len(recommendations) < settings.RECOMMEND_TOP_N:
            all_knowledges = self.db.list_knowledges()
            same_category = [
                k for k in all_knowledges
                if k.knowledge_id != matched_knowledge.knowledge_id
                and k.knowledge_category == matched_knowledge.knowledge_category
            ]
            same_category.sort(key=lambda x: x.view_count, reverse=True)
            
            for k in same_category[:settings.RECOMMEND_TOP_N - len(recommendations)]:
                recommendations.append(
                    self._knowledge_to_item(k, 0.7, "related")
                )
        
        return recommendations[:settings.RECOMMEND_TOP_N]
    
    def _get_hot_recommendations(self) -> List[RecommendItem]:
        all_knowledges = self.db.list_knowledges()
        hot_knowledges = [
            k for k in all_knowledges
            if k.view_count >= settings.HOT_RECOMMEND_MIN_VIEWS
        ]
        
        if not hot_knowledges:
            hot_knowledges = sorted(all_knowledges, key=lambda x: x.view_count, reverse=True)
        else:
            hot_knowledges.sort(key=lambda x: x.view_count, reverse=True)
        
        max_views = hot_knowledges[0].view_count if hot_knowledges else 1
        
        recommendations = []
        for k in hot_knowledges[:settings.RECOMMEND_TOP_N]:
            score = round(k.view_count / max_views, 2) if max_views > 0 else 0.5
            recommendations.append(
                self._knowledge_to_item(k, score, "hot")
            )
        
        return recommendations
    
    def _do_generate_recommendations(
        self,
        matched_knowledge: Optional[Knowledge] = None,
        qa_id: Optional[str] = None
    ) -> List[RecommendItem]:
        recommendations = []
        
        if matched_knowledge:
            related = self._get_related_recommendations(matched_knowledge)
            recommendations.extend(related)
        
        if len(recommendations) < settings.RECOMMEND_TOP_N * 2:
            hot = self._get_hot_recommendations()
            existing_ids = {r.knowledge_id for r in recommendations}
            for item in hot:
                if item.knowledge_id not in existing_ids:
                    recommendations.append(item)
                    if len(recommendations) >= settings.RECOMMEND_TOP_N * 2:
                        break
        
        recommendations = recommendations[:settings.RECOMMEND_TOP_N]
        
        if qa_id and recommendations:
            knowledge_ids = [r.knowledge_id for r in recommendations]
            self.db.create_recommend_record(qa_id, knowledge_ids, "combined")
        
        return recommendations
    
    def generate_recommendations(
        self,
        matched_knowledge: Optional[Knowledge] = None,
        qa_id: Optional[str] = None,
        use_async: bool = False
    ) -> List[RecommendItem]:
        if use_async and qa_id:
            matched_id = matched_knowledge.knowledge_id if matched_knowledge else None
            queue_manager.submit_task(qa_id, matched_id)
            
            if not queue_manager.worker or not queue_manager.worker._running:
                self.start_worker()
            
            return []
        
        return self._do_generate_recommendations(matched_knowledge, qa_id)
    
    def submit_async_recommendation(
        self,
        qa_id: str,
        matched_knowledge_id: Optional[str] = None
    ) -> str:
        self.start_worker()
        return queue_manager.submit_task(qa_id, matched_knowledge_id)
    
    def get_async_result(self, qa_id: str) -> Optional[Dict[str, Any]]:
        return queue_manager.get_result(qa_id)
    
    def get_queue_stats(self) -> Dict[str, int]:
        return queue_manager.get_queue_stats()
    
    def generate_recommendations_with_ids(
        self,
        matched_knowledge_id: Optional[str] = None,
        qa_id: Optional[str] = None
    ) -> List[RecommendItem]:
        matched_knowledge = None
        if matched_knowledge_id:
            matched_knowledge = self.db.get_knowledge(matched_knowledge_id)
        
        return self._do_generate_recommendations(matched_knowledge, qa_id)
