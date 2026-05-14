from typing import List, Optional
from qabot.models import Database, QARecord, QARecordCreate


class HistoryModule:
    def __init__(self, db: Database):
        self.db = db
    
    def record_qa(
        self,
        data: QARecordCreate,
        reply_content: str,
        reply_type: str,
        matched_knowledge: Optional[str] = None,
        match_score: Optional[float] = None,
        intent_category: Optional[str] = None
    ) -> QARecord:
        return self.db.create_qa_record(
            data,
            reply_content=reply_content,
            reply_type=reply_type,
            matched_knowledge=matched_knowledge,
            match_score=match_score,
            intent_category=intent_category
        )
    
    def get_qa_record(self, qa_id: str) -> Optional[QARecord]:
        return self.db.get_qa_record(qa_id)
    
    def list_user_history(self, user_id: str, limit: int = 100) -> List[QARecord]:
        return self.db.list_qa_records(user_id=user_id, limit=limit)
    
    def list_all_history(self, limit: int = 100) -> List[QARecord]:
        return self.db.list_qa_records(limit=limit)
