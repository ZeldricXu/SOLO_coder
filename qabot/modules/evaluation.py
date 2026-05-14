from typing import Optional, Dict, List, Any
from collections import Counter
from qabot.models import Database, QARecord, Knowledge, KnowledgeUpdate
from qabot.config import settings


class KeywordOptimizer:
    def __init__(self, db: Database):
        self.db = db
    
    def analyze_low_satisfaction_questions(self, knowledge_id: str) -> List[str]:
        return self.db.get_low_satisfaction_keywords(knowledge_id)
    
    def extract_keywords_from_content(self, knowledge: Knowledge) -> List[str]:
        content = knowledge.knowledge_content.lower()
        title = knowledge.knowledge_title.lower()
        
        stop_words = {
            "的", "了", "是", "在", "我", "有", "和", "就", "不", "人", "都", "一", "一个",
            "上", "也", "很", "到", "说", "要", "去", "你", "会", "着", "没有", "看", "好",
            "自己", "这", "那", "他", "她", "它", "们", "这个", "那个", "什么", "怎么",
            "如何", "为什么", "怎么办", "可以", "请", "如果", "或者", "但是", "因为", "所以",
            "如果", "然后", "之后", "之前", "现在", "应该", "需要", "能够", "进行", "使用"
        }
        
        all_text = title + " " + content
        words = []
        for word in all_text.split():
            word = word.strip('.,?!；：""\'\'()（）【】[]')
            if word and len(word) >= 2 and word not in stop_words:
                words.append(word)
        
        counter = Counter(words)
        common = [w for w, c in counter.most_common(10) if c >= 1]
        return common
    
    def should_optimize(self, knowledge_id: str) -> bool:
        if not settings.feedback.ENABLE_AUTO_OPTIMIZATION:
            return False
        
        needing_optimization = self.db.get_knowledge_needing_optimization()
        return knowledge_id in needing_optimization
    
    def optimize_keywords(self, knowledge_id: str) -> Optional[Dict[str, Any]]:
        knowledge = self.db.get_knowledge(knowledge_id)
        if not knowledge:
            return None
        
        low_satisfaction_keywords = self.analyze_low_satisfaction_questions(knowledge_id)
        content_keywords = self.extract_keywords_from_content(knowledge)
        
        existing_keywords = set(knowledge.knowledge_keywords)
        all_candidates = set(low_satisfaction_keywords + content_keywords)
        
        keywords_to_add = []
        for kw in all_candidates:
            if kw not in existing_keywords:
                keywords_to_add.append(kw)
        
        max_allowed = settings.feedback.MAX_KEYWORDS_PER_KNOWLEDGE
        if len(existing_keywords) + len(keywords_to_add) > max_allowed:
            slots_available = max_allowed - len(existing_keywords)
            if slots_available > 0:
                keywords_to_add = keywords_to_add[:slots_available]
            else:
                keywords_to_add = []
        
        keywords_to_remove = []
        if len(existing_keywords) + len(keywords_to_add) > max_allowed:
            for kw in list(existing_keywords):
                if kw not in low_satisfaction_keywords and kw not in content_keywords:
                    keywords_to_remove.append(kw)
                if len(existing_keywords) - len(keywords_to_remove) + len(keywords_to_add) <= max_allowed:
                    break
        
        if not keywords_to_add and not keywords_to_remove:
            return None
        
        new_keywords = set(existing_keywords)
        new_keywords.update(keywords_to_add)
        for kw in keywords_to_remove:
            new_keywords.discard(kw)
        
        update_data = KnowledgeUpdate(knowledge_keywords=list(new_keywords))
        updated_knowledge = self.db.update_knowledge(knowledge_id, update_data)
        
        if updated_knowledge:
            self.db.record_keyword_optimization(knowledge_id, keywords_to_add, keywords_to_remove)
            
            return {
                "knowledge_id": knowledge_id,
                "added_keywords": keywords_to_add,
                "removed_keywords": keywords_to_remove,
                "new_keywords": list(new_keywords),
                "optimized_at": updated_knowledge.updated_at
            }
        
        return None
    
    def get_optimization_history(self, knowledge_id: Optional[str] = None) -> List[Dict]:
        history = self.db.feedback_data.optimization_history
        if knowledge_id:
            return [h for h in history if h.get("knowledge_id") == knowledge_id]
        return history
    
    def get_feedback_statistics(self) -> Dict[str, Any]:
        feedback_data = self.db.feedback_data
        
        total_feedback = len(feedback_data.feedback_history)
        low_satisfaction_total = sum(
            1 for f in feedback_data.feedback_history 
            if f.get("satisfaction", 0) <= 2
        )
        high_satisfaction_total = sum(
            1 for f in feedback_data.feedback_history 
            if f.get("satisfaction", 0) >= 4
        )
        medium_satisfaction_total = sum(
            1 for f in feedback_data.feedback_history 
            if 2 < f.get("satisfaction", 0) < 4
        )
        
        avg_satisfaction = None
        if total_feedback > 0:
            total_score = sum(f.get("satisfaction", 0) for f in feedback_data.feedback_history)
            avg_satisfaction = round(total_score / total_feedback, 2)
        
        return {
            "total_feedback": total_feedback,
            "low_satisfaction_count": low_satisfaction_total,
            "medium_satisfaction_count": medium_satisfaction_total,
            "high_satisfaction_count": high_satisfaction_total,
            "average_satisfaction": avg_satisfaction,
            "optimization_count": len(feedback_data.optimization_history),
            "knowledge_needing_optimization": self.db.get_knowledge_needing_optimization()
        }


class EvaluationModule:
    def __init__(self, db: Database):
        self.db = db
        self.keyword_optimizer = KeywordOptimizer(db)
    
    def evaluate_quality(self, qa_id: str, satisfaction: int) -> Optional[QARecord]:
        if satisfaction < 1 or satisfaction > 5:
            return None
        
        record = self.db.update_qa_satisfaction(qa_id, satisfaction)
        if record:
            self.db.add_satisfaction(satisfaction)
            self.db.record_feedback_analysis(record, satisfaction)
            
            if satisfaction <= settings.feedback.LOW_SATISFACTION_THRESHOLD:
                if self.keyword_optimizer.should_optimize(record.qa_id):
                    pass
        
        return record
    
    def get_feedback_info(self, qa_id: str) -> Optional[dict]:
        record = self.db.get_qa_record(qa_id)
        if not record:
            return None
        
        return {
            "qa_id": record.qa_id,
            "question": record.question,
            "satisfaction": record.satisfaction,
            "matched_knowledge": record.matched_knowledge,
            "reply_type": record.reply_type,
            "match_score": record.match_score
        }
    
    def get_keyword_optimizer(self) -> KeywordOptimizer:
        return self.keyword_optimizer
    
    def run_auto_optimization(self, knowledge_id: Optional[str] = None) -> List[Dict[str, Any]]:
        if not settings.feedback.ENABLE_AUTO_OPTIMIZATION:
            return []
        
        results = []
        
        if knowledge_id:
            result = self.keyword_optimizer.optimize_keywords(knowledge_id)
            if result:
                results.append(result)
        else:
            needing_optimization = self.db.get_knowledge_needing_optimization()
            for kid in needing_optimization:
                result = self.keyword_optimizer.optimize_keywords(kid)
                if result:
                    results.append(result)
        
        return results
    
    def get_feedback_statistics(self) -> Dict[str, Any]:
        return self.keyword_optimizer.get_feedback_statistics()
    
    def get_optimization_history(self, knowledge_id: Optional[str] = None) -> List[Dict]:
        return self.keyword_optimizer.get_optimization_history(knowledge_id)
