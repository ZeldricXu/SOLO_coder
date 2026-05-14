import logging
import jieba
from typing import List, Dict, Any, Optional
from searchengine.models.base import SearchRequest, SearchIndex, SearchResultItem


class QueryProcessor:
    def __init__(self):
        self.logger = logging.getLogger(__name__)
    
    def parse_request(self, request: SearchRequest) -> Dict[str, Any]:
        parsed = {
            "keyword": request.keyword.strip(),
            "filters": request.filters,
            "sort_type": request.sort_type,
            "page": request.page,
            "page_size": request.page_size,
            "keywords_tokens": self._tokenize(request.keyword)
        }
        self.logger.info(f"Parsed query: {parsed}")
        return parsed
    
    def _tokenize(self, text: str) -> List[str]:
        try:
            tokens = list(jieba.cut(text, cut_all=False))
            return [t for t in tokens if t and t.strip()]
        except Exception as e:
            self.logger.warning(f"Tokenization failed: {e}")
            return [text]
    
    def extract_keywords(self, text: str) -> List[str]:
        return self._tokenize(text)
    
    def calculate_relevance(self, index: SearchIndex, keywords: List[str]) -> float:
        if not keywords:
            return 0.0
        
        title_score = self._calculate_field_score(index.title, keywords, 1.5)
        content_score = self._calculate_field_score(index.content, keywords, 1.0)
        keyword_score = self._calculate_keyword_list_score(index.keywords, keywords, 2.0)
        
        total_weight = 1.5 + 1.0 + 2.0
        total_score = (title_score + content_score + keyword_score) / total_weight
        
        click_factor = min(1.0 + (index.click_count / 1000.0) * 0.5, 1.5)
        final_score = total_score * click_factor
        
        return round(final_score, 4)
    
    def _calculate_field_score(self, text: str, keywords: List[str], weight: float) -> float:
        if not text:
            return 0.0
        
        text_lower = text.lower()
        match_count = 0
        total_length = 0
        
        for keyword in keywords:
            keyword_lower = keyword.lower()
            if keyword_lower in text_lower:
                match_count += 1
                total_length += len(keyword)
        
        if match_count == 0:
            return 0.0
        
        coverage = match_count / len(keywords)
        density = min(total_length / len(text_lower) * 10, 1.0)
        
        return (coverage * 0.7 + density * 0.3) * weight
    
    def _calculate_keyword_list_score(self, keywords_list: List[str], search_keywords: List[str], weight: float) -> float:
        if not keywords_list:
            return 0.0
        
        match_count = 0
        keywords_lower = [kw.lower() for kw in keywords_list]
        
        for keyword in search_keywords:
            keyword_lower = keyword.lower()
            if any(keyword_lower in kw for kw in keywords_lower):
                match_count += 1
        
        if match_count == 0:
            return 0.0
        
        coverage = match_count / len(search_keywords)
        return coverage * weight
    
    def validate_filters(self, filters: Dict[str, Any]) -> bool:
        if not filters:
            return True
        if not isinstance(filters, dict):
            return False
        return True
    
    def build_cache_key(self, request: SearchRequest) -> str:
        import hashlib
        import json
        
        cache_data = {
            "keyword": request.keyword,
            "filters": dict(sorted(request.filters.items())) if request.filters else {},
            "sort_type": request.sort_type,
            "page": request.page,
            "page_size": request.page_size
        }
        json_str = json.dumps(cache_data, sort_keys=True, default=str)
        return f"search:query:{hashlib.md5(json_str.encode()).hexdigest()}"


query_processor = QueryProcessor()
