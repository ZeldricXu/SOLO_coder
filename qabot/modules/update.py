from typing import List, Optional, Dict, Any
from datetime import datetime, timezone
from qabot.models import Database, Knowledge, KnowledgeUpdate
from qabot.config import settings


def parse_iso_time(time_str: str) -> Optional[datetime]:
    try:
        if time_str.endswith('Z'):
            time_str = time_str[:-1] + '+00:00'
        return datetime.fromisoformat(time_str)
    except (ValueError, TypeError):
        return None


class IndexManager:
    def __init__(self, db: Database):
        self.db = db
    
    def get_knowledge_activity_level(self, knowledge_id: str) -> str:
        knowledge = self.db.get_knowledge(knowledge_id)
        if not knowledge:
            return "low"
        
        activity_score = self.db.get_index_activity_score(knowledge_id)
        view_count = knowledge.view_count + activity_score
        
        if view_count >= settings.index_update.HIGH_ACTIVITY_THRESHOLD:
            return "high"
        elif view_count >= settings.index_update.MEDIUM_ACTIVITY_THRESHOLD:
            return "medium"
        else:
            return "low"
    
    def get_update_interval(self, knowledge_id: str) -> int:
        knowledge = self.db.get_knowledge(knowledge_id)
        if not knowledge:
            return settings.index_update.LOW_ACTIVITY_INTERVAL
        
        return settings.calculate_update_interval(knowledge.view_count)
    
    def needs_update(self, knowledge_id: str) -> bool:
        knowledge = self.db.get_knowledge(knowledge_id)
        if not knowledge:
            return False
        
        if knowledge.needs_update:
            return True
        
        interval = self.get_update_interval(knowledge_id)
        last_update = self.db.index_metadata.get_last_update(knowledge_id)
        
        if not last_update:
            return True
        
        last_update_time = parse_iso_time(last_update)
        if not last_update_time:
            return True
        
        now = datetime.now(timezone.utc)
        elapsed_seconds = (now - last_update_time).total_seconds()
        
        return elapsed_seconds >= interval
    
    def get_all_knowledge_activity_report(self) -> List[Dict[str, Any]]:
        report = []
        knowledges = self.db.list_knowledges()
        
        for k in knowledges:
            activity_level = self.get_knowledge_activity_level(k.knowledge_id)
            interval = self.get_update_interval(k.knowledge_id)
            needs_upd = self.needs_update(k.knowledge_id)
            activity_score = self.db.get_index_activity_score(k.knowledge_id)
            
            report.append({
                "knowledge_id": k.knowledge_id,
                "knowledge_title": k.knowledge_title,
                "view_count": k.view_count,
                "activity_score": activity_score,
                "activity_level": activity_level,
                "update_interval_seconds": interval,
                "needs_update": needs_upd,
                "last_update": self.db.index_metadata.get_last_update(k.knowledge_id)
            })
        
        report.sort(key=lambda x: (x["activity_level"] != "high", 
                                   x["activity_level"] != "medium",
                                   x["view_count"]), reverse=True)
        return report
    
    def get_knowledge_needing_dynamic_update(self) -> List[str]:
        knowledges = self.db.list_knowledges()
        needing_update = []
        
        for k in knowledges:
            if self.needs_update(k.knowledge_id):
                needing_update.append(k.knowledge_id)
        
        return needing_update


class UpdateModule:
    def __init__(self, db: Database):
        self.db = db
        self.index_manager = IndexManager(db)
    
    def update_knowledge(self, knowledge_id: str, data: KnowledgeUpdate) -> Optional[Knowledge]:
        return self.db.update_knowledge(knowledge_id, data)
    
    def mark_for_update(self, knowledge_id: str) -> bool:
        return self.db.mark_knowledge_needs_update(knowledge_id)
    
    def get_knowledge_needing_update(self) -> List[Knowledge]:
        all_knowledges = self.db.list_knowledges()
        return [k for k in all_knowledges if k.needs_update]
    
    def increment_view_count(self, knowledge_id: str):
        self.db.increment_knowledge_view(knowledge_id)
    
    def get_index_manager(self) -> IndexManager:
        return self.index_manager
    
    def get_activity_report(self) -> List[Dict[str, Any]]:
        return self.index_manager.get_all_knowledge_activity_report()
