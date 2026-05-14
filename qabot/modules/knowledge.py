from typing import List, Optional
from qabot.models import Database, Knowledge, KnowledgeCreate, KnowledgeUpdate, KnowledgeInList


class KnowledgeModule:
    def __init__(self, db: Database):
        self.db = db
    
    def create_knowledge(self, data: KnowledgeCreate) -> Knowledge:
        return self.db.create_knowledge(data)
    
    def get_knowledge(self, knowledge_id: str) -> Optional[Knowledge]:
        return self.db.get_knowledge(knowledge_id)
    
    def list_knowledges(self, category: Optional[str] = None) -> List[KnowledgeInList]:
        knowledges = self.db.list_knowledges(category)
        return [
            KnowledgeInList(
                knowledge_id=k.knowledge_id,
                knowledge_title=k.knowledge_title,
                knowledge_category=k.knowledge_category,
                knowledge_tags=k.knowledge_tags,
                view_count=k.view_count
            )
            for k in knowledges
        ]
    
    def list_categories(self) -> List[str]:
        knowledges = self.db.list_knowledges()
        categories = set(k.knowledge_category for k in knowledges)
        return sorted(list(categories))
