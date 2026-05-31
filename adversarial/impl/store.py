from typing import List, Dict, Optional
from ..protocols import AttackHistoryStore, AssessmentCache
from ..schemas import AdversarialExample, SecurityAssessmentResponse


class InMemoryAttackHistoryStore(AttackHistoryStore):
    def __init__(self):
        self._history: Dict[str, List[AdversarialExample]] = {}

    def save(self, request_id: str, examples: List[AdversarialExample]) -> None:
        self._history[request_id] = examples

    def get(self, request_id: str) -> Optional[List[AdversarialExample]]:
        return self._history.get(request_id)


class InMemoryAssessmentCache(AssessmentCache):
    def __init__(self):
        self._cache: Dict[str, SecurityAssessmentResponse] = {}

    def save(self, assessment_id: str, response: SecurityAssessmentResponse) -> None:
        self._cache[assessment_id] = response

    def get(self, assessment_id: str) -> Optional[SecurityAssessmentResponse]:
        return self._cache.get(assessment_id)
