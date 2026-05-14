import logging
import uuid
from typing import List, Dict, Any, Optional, Set
from datetime import datetime
from collections import defaultdict
from searchengine.models.base import SearchIndex, RecommendResult, RecommendItem, RecommendRequest
from searchengine.modules.index_manager import index_manager


class RecommendModule:
    def __init__(self):
        self.logger = logging.getLogger(__name__)
        self._user_history: Dict[str, List[str]] = defaultdict(list)
        self._recommend_counter = 0
    
    def _generate_recommend_id(self) -> str:
        self._recommend_counter += 1
        return f"recommend_{self._recommend_counter:03d}"
    
    def generate_recommendations(self, request: RecommendRequest) -> RecommendResult:
        if request.recommend_type == "related":
            return self._generate_related_recommendations(request)
        elif request.recommend_type == "hot":
            return self._generate_hot_recommendations(request)
        elif request.recommend_type == "personalized":
            return self._generate_personalized_recommendations(request)
        else:
            return self._generate_hot_recommendations(request)
    
    def _generate_related_recommendations(self, request: RecommendRequest) -> RecommendResult:
        self.logger.info(f"Generating related recommendations for content: {request.content_id}")
        
        reference_index = None
        if request.content_id:
            reference_index = index_manager.get_index_by_content_id(request.content_id)
        
        all_indexes = index_manager.get_all_indexes()
        
        if not all_indexes:
            return RecommendResult(
                recommend_id=self._generate_recommend_id(),
                user_id=request.user_id,
                content_id=request.content_id,
                recommend_type="related",
                recommend_items=[],
                generated_at=datetime.utcnow()
            )
        
        scored_items = []
        reference_keywords = set()
        reference_category = None
        
        if reference_index:
            reference_keywords = set(k.lower() for k in reference_index.keywords)
            reference_category = reference_index.category
        
        for index in all_indexes:
            if request.content_id and index.content_id == request.content_id:
                continue
            
            score = self._calculate_related_score(
                index,
                reference_index,
                reference_keywords,
                reference_category
            )
            
            if score > 0:
                scored_items.append({
                    "index": index,
                    "score": score
                })
        
        scored_items.sort(key=lambda x: x["score"], reverse=True)
        scored_items = scored_items[:request.limit]
        
        recommend_items = []
        for pos, item in enumerate(scored_items, start=1):
            recommend_items.append(RecommendItem(
                content_id=item["index"].content_id,
                title=item["index"].title,
                recommend_score=round(item["score"], 4)
            ))
        
        if request.user_id and request.content_id:
            self._update_user_history(request.user_id, request.content_id)
        
        return RecommendResult(
            recommend_id=self._generate_recommend_id(),
            user_id=request.user_id,
            content_id=request.content_id,
            recommend_type="related",
            recommend_items=recommend_items,
            generated_at=datetime.utcnow()
        )
    
    def _generate_hot_recommendations(self, request: RecommendRequest) -> RecommendResult:
        self.logger.info(f"Generating hot recommendations")
        
        all_indexes = index_manager.get_all_indexes()
        
        if not all_indexes:
            return RecommendResult(
                recommend_id=self._generate_recommend_id(),
                user_id=request.user_id,
                content_id=request.content_id,
                recommend_type="hot",
                recommend_items=[],
                generated_at=datetime.utcnow()
            )
        
        scored_items = []
        max_click = max((i.click_count for i in all_indexes), default=1)
        
        for index in all_indexes:
            click_score = index.click_count / max_click if max_click > 0 else 0
            time_score = self._calculate_time_score(index)
            
            final_score = click_score * 0.7 + time_score * 0.3
            
            scored_items.append({
                "index": index,
                "score": final_score
            })
        
        scored_items.sort(key=lambda x: x["score"], reverse=True)
        scored_items = scored_items[:request.limit]
        
        recommend_items = []
        for pos, item in enumerate(scored_items, start=1):
            recommend_items.append(RecommendItem(
                content_id=item["index"].content_id,
                title=item["index"].title,
                recommend_score=round(item["score"], 4)
            ))
        
        return RecommendResult(
            recommend_id=self._generate_recommend_id(),
            user_id=request.user_id,
            content_id=request.content_id,
            recommend_type="hot",
            recommend_items=recommend_items,
            generated_at=datetime.utcnow()
        )
    
    def _generate_personalized_recommendations(self, request: RecommendRequest) -> RecommendResult:
        self.logger.info(f"Generating personalized recommendations for user: {request.user_id}")
        
        all_indexes = index_manager.get_all_indexes()
        
        if not all_indexes:
            return RecommendResult(
                recommend_id=self._generate_recommend_id(),
                user_id=request.user_id,
                content_id=request.content_id,
                recommend_type="personalized",
                recommend_items=[],
                generated_at=datetime.utcnow()
            )
        
        user_history = self._user_history.get(request.user_id, []) if request.user_id else []
        
        user_preferences = self._extract_user_preferences(user_history)
        
        scored_items = []
        
        for index in all_indexes:
            score = self._calculate_personalized_score(index, user_preferences)
            
            if request.content_id and index.content_id == request.content_id:
                score *= 0.5
            
            scored_items.append({
                "index": index,
                "score": score
            })
        
        scored_items.sort(key=lambda x: x["score"], reverse=True)
        scored_items = scored_items[:request.limit]
        
        recommend_items = []
        for pos, item in enumerate(scored_items, start=1):
            recommend_items.append(RecommendItem(
                content_id=item["index"].content_id,
                title=item["index"].title,
                recommend_score=round(item["score"], 4)
            ))
        
        return RecommendResult(
            recommend_id=self._generate_recommend_id(),
            user_id=request.user_id,
            content_id=request.content_id,
            recommend_type="personalized",
            recommend_items=recommend_items,
            generated_at=datetime.utcnow()
        )
    
    def _calculate_related_score(self,
                                  index: SearchIndex,
                                  reference_index: Optional[SearchIndex],
                                  reference_keywords: Set[str],
                                  reference_category: Optional[str]) -> float:
        if not reference_index:
            return self._calculate_hot_score(index)
        
        keyword_overlap = 0
        for keyword in index.keywords:
            if keyword.lower() in reference_keywords:
                keyword_overlap += 1
        
        keyword_score = keyword_overlap / max(len(reference_keywords), 1)
        
        category_score = 0.0
        if reference_category and index.category == reference_category:
            category_score = 0.5
        
        author_score = 0.0
        if reference_index.author and index.author == reference_index.author:
            author_score = 0.3
        
        click_factor = min(index.click_count / 1000.0, 0.2)
        
        final_score = keyword_score * 0.5 + category_score + author_score + click_factor
        
        return min(final_score, 1.0)
    
    def _calculate_hot_score(self, index: SearchIndex) -> float:
        time_score = self._calculate_time_score(index)
        click_factor = min(index.click_count / 100.0, 1.0)
        
        return time_score * 0.3 + click_factor * 0.7
    
    def _calculate_time_score(self, index: SearchIndex) -> float:
        now = datetime.utcnow()
        reference_time = index.publish_time or index.index_time
        
        if not reference_time:
            return 0.5
        
        if isinstance(reference_time, str):
            try:
                reference_time = datetime.fromisoformat(reference_time.replace("Z", "+00:00"))
            except:
                return 0.5
        
        age_days = (now - reference_time).days
        max_days = 30
        
        if age_days <= 0:
            return 1.0
        elif age_days >= max_days:
            return 0.1
        else:
            return 1.0 - (age_days / max_days) * 0.9
    
    def _extract_user_preferences(self, user_history: List[str]) -> Dict[str, Any]:
        preferences = {
            "keywords": defaultdict(int),
            "categories": defaultdict(int),
            "authors": defaultdict(int)
        }
        
        for content_id in user_history[-10:]:
            index = index_manager.get_index_by_content_id(content_id)
            if index:
                for keyword in index.keywords:
                    preferences["keywords"][keyword.lower()] += 1
                if index.category:
                    preferences["categories"][index.category] += 1
                if index.author:
                    preferences["authors"][index.author] += 1
        
        return preferences
    
    def _calculate_personalized_score(self,
                                       index: SearchIndex,
                                       user_preferences: Dict[str, Any]) -> float:
        if not user_preferences["keywords"] and not user_preferences["categories"] and not user_preferences["authors"]:
            return self._calculate_hot_score(index)
        
        keyword_score = 0.0
        total_keyword_weight = sum(user_preferences["keywords"].values())
        if total_keyword_weight > 0:
            matched = 0
            for keyword in index.keywords:
                matched += user_preferences["keywords"].get(keyword.lower(), 0)
            keyword_score = matched / total_keyword_weight
        
        category_score = 0.0
        total_category_weight = sum(user_preferences["categories"].values())
        if total_category_weight > 0 and index.category:
            category_score = user_preferences["categories"].get(index.category, 0) / total_category_weight
        
        author_score = 0.0
        total_author_weight = sum(user_preferences["authors"].values())
        if total_author_weight > 0 and index.author:
            author_score = user_preferences["authors"].get(index.author, 0) / total_author_weight
        
        hot_score = self._calculate_hot_score(index) * 0.3
        
        final_score = (
            keyword_score * 0.4 +
            category_score * 0.2 +
            author_score * 0.1 +
            hot_score
        )
        
        return min(final_score, 1.0)
    
    def _update_user_history(self, user_id: str, content_id: str) -> None:
        if content_id not in self._user_history[user_id][-5:]:
            self._user_history[user_id].append(content_id)
        
        if len(self._user_history[user_id]) > 50:
            self._user_history[user_id] = self._user_history[user_id][-50:]
    
    def get_user_history(self, user_id: str) -> List[str]:
        return self._user_history.get(user_id, []).copy()
    
    def clear_user_history(self, user_id: str) -> None:
        if user_id in self._user_history:
            del self._user_history[user_id]


recommend_module = RecommendModule()
