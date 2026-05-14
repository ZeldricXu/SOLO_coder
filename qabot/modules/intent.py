from typing import Optional
from qabot.models import Database, Intent, IntentCreate


class IntentModule:
    def __init__(self, db: Database):
        self.db = db
    
    def recognize_intent(self, question: str) -> Optional[str]:
        question_lower = question.lower()
        
        for intent in self.db.list_intents():
            for keyword in intent.intent_keywords:
                if keyword.lower() in question_lower:
                    return intent.intent_category
        
        return "general"
    
    def list_intents(self) -> list[Intent]:
        return self.db.list_intents()
    
    def create_intent(self, data: IntentCreate) -> Intent:
        return self.db.create_intent(data)
