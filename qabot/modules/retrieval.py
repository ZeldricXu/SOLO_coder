from typing import List, Tuple, Optional, Dict
import math
from collections import Counter

from qabot.models import Database, Knowledge
from qabot.config import settings


class RetrievalResult:
    def __init__(self, knowledge: Knowledge, keyword_score: float, semantic_score: float, combined_score: float):
        self.knowledge = knowledge
        self.keyword_score = keyword_score
        self.semantic_score = semantic_score
        self.combined_score = combined_score


class RetrievalModule:
    def __init__(self, db: Database):
        self.db = db
    
    def _extract_question_keywords(self, question: str) -> List[str]:
        question_lower = question.lower()
        stop_words = {"怎么", "如何", "为什么", "什么", "是", "的", "了", "吗", "呢", "啊", "呀", "吧"}
        keywords = []
        for word in question_lower.split():
            if word and word not in stop_words and len(word) > 1:
                keywords.append(word)
        return keywords
    
    def _calculate_keyword_score(self, question: str, knowledge: Knowledge) -> float:
        question_keywords = self._extract_question_keywords(question)
        if not question_keywords and not knowledge.knowledge_keywords:
            return 0.0
        
        question_lower = question.lower()
        match_count = 0
        
        for keyword in knowledge.knowledge_keywords:
            keyword_lower = keyword.lower()
            if keyword_lower in question_lower:
                match_count += 2
        
        for keyword in question_keywords:
            if keyword in knowledge.knowledge_content.lower():
                match_count += 1
            if keyword in knowledge.knowledge_title.lower():
                match_count += 1
        
        total_keywords = max(len(knowledge.knowledge_keywords) + len(question_keywords), 1)
        score = min(match_count / total_keywords, 1.0)
        return score
    
    def _tokenize(self, text: str) -> List[str]:
        text_lower = text.lower()
        tokens = []
        for word in text_lower.split():
            if word and len(word) > 1:
                tokens.append(word)
        return tokens
    
    def _calculate_tfidf_similarity(self, text1: str, text2: str) -> float:
        tokens1 = self._tokenize(text1)
        tokens2 = self._tokenize(text2)
        
        if not tokens1 or not tokens2:
            return 0.0
        
        counter1 = Counter(tokens1)
        counter2 = Counter(tokens2)
        
        all_tokens = set(counter1.keys()).union(set(counter2.keys()))
        
        dot_product = 0
        magnitude1 = 0
        magnitude2 = 0
        
        for token in all_tokens:
            tf1 = counter1.get(token, 0)
            tf2 = counter2.get(token, 0)
            
            dot_product += tf1 * tf2
            magnitude1 += tf1 ** 2
            magnitude2 += tf2 ** 2
        
        if magnitude1 == 0 or magnitude2 == 0:
            return 0.0
        
        return dot_product / (math.sqrt(magnitude1) * math.sqrt(magnitude2))
    
    def _calculate_semantic_score(self, question: str, knowledge: Knowledge) -> float:
        title_similarity = self._calculate_tfidf_similarity(question, knowledge.knowledge_title)
        content_similarity = self._calculate_tfidf_similarity(question, knowledge.knowledge_content)
        tag_similarity = 0.0
        if knowledge.knowledge_tags:
            tag_text = " ".join(knowledge.knowledge_tags)
            tag_similarity = self._calculate_tfidf_similarity(question, tag_text)
        
        return title_similarity * 0.4 + content_similarity * 0.5 + tag_similarity * 0.1
    
    def _calculate_combined_score(self, keyword_score: float, semantic_score: float) -> float:
        return (
            keyword_score * settings.KEYWORD_MATCH_WEIGHT +
            semantic_score * settings.SEMANTIC_MATCH_WEIGHT
        )
    
    def retrieve(self, question: str, intent_category: Optional[str] = None) -> List[RetrievalResult]:
        knowledges = self.db.list_knowledges()
        if intent_category:
            intent_to_category = {
                "account": "账户管理",
                "payment": "支付管理",
                "product": "产品使用",
                "service": "服务支持"
            }
            category = intent_to_category.get(intent_category)
            if category:
                category_knowledges = [k for k in knowledges if k.knowledge_category == category]
                if category_knowledges:
                    knowledges = category_knowledges
        
        results = []
        for knowledge in knowledges:
            keyword_score = self._calculate_keyword_score(question, knowledge)
            semantic_score = self._calculate_semantic_score(question, knowledge)
            combined_score = self._calculate_combined_score(keyword_score, semantic_score)
            
            if combined_score >= settings.MIN_MATCH_SCORE:
                results.append(RetrievalResult(
                    knowledge=knowledge,
                    keyword_score=keyword_score,
                    semantic_score=semantic_score,
                    combined_score=combined_score
                ))
        
        if settings.ENABLE_KEYWORD_PRIORITY:
            results.sort(key=lambda x: x.combined_score, reverse=True)
        else:
            results.sort(key=lambda x: x.semantic_score, reverse=True)
        
        return results[:settings.RETRIEVAL_TOP_K]
    
    def get_best_match(self, question: str, intent_category: Optional[str] = None) -> Optional[RetrievalResult]:
        results = self.retrieve(question, intent_category)
        if results:
            return results[0]
        return None
