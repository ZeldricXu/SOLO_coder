from typing import Optional
from qabot.models import Database, QAStatsResponse


class AnalysisModule:
    def __init__(self, db: Database):
        self.db = db
    
    def record_question(self, matched: bool):
        self.db.increment_total_questions()
        if matched:
            self.db.increment_matched_questions()
        else:
            self.db.increment_unmatched_questions()
    
    def record_satisfaction(self, score: int):
        if 1 <= score <= 5:
            self.db.add_satisfaction(score)
    
    def get_stats(self, stat_date: Optional[str] = None) -> Optional[QAStatsResponse]:
        stats = self.db.get_stats(stat_date)
        if not stats:
            return None
        
        avg_satisfaction = None
        if stats.satisfaction_count > 0:
            avg_satisfaction = round(stats.total_satisfaction_score / stats.satisfaction_count, 2)
        
        matched_rate = 0.0
        if stats.total_questions > 0:
            matched_rate = round(stats.matched_questions / stats.total_questions * 100, 2)
        
        return QAStatsResponse(
            stat_date=stats.stat_date,
            total_questions=stats.total_questions,
            matched_questions=stats.matched_questions,
            unmatched_questions=stats.unmatched_questions,
            avg_satisfaction=avg_satisfaction,
            matched_rate=matched_rate
        )
