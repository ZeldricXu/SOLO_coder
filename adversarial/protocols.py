from abc import ABC, abstractmethod
from typing import List, Dict, Any, Optional
from datetime import datetime

from .schemas import (
    AdversarialAttackRequest,
    AdversarialAttackResponse,
    SecurityAssessmentRequest,
    SecurityAssessmentResponse,
    AdversarialExample,
    AttackResult,
    SecurityMetrics,
)


class AttackGenerator(ABC):
    @abstractmethod
    async def generate(
        self,
        request: AdversarialAttackRequest,
    ) -> AdversarialAttackResponse:
        pass


class SecurityAssessor(ABC):
    @abstractmethod
    async def assess(
        self,
        request: SecurityAssessmentRequest,
    ) -> SecurityAssessmentResponse:
        pass


class AttackResultAggregator(ABC):
    @abstractmethod
    def calculate_risk_score(self, results: List[AttackResult]) -> float:
        pass

    @abstractmethod
    def calculate_security_score(self, metrics: List[SecurityMetrics]) -> float:
        pass

    @abstractmethod
    def determine_risk_level(self, score: float) -> str:
        pass


class RecommendationEngine(ABC):
    @abstractmethod
    def generate_attack_recommendations(
        self, results: List[AttackResult], risk_score: float
    ) -> List[str]:
        pass

    @abstractmethod
    def generate_security_recommendations(
        self, metrics: List[SecurityMetrics], risk_level: str
    ) -> List[str]:
        pass


class AttackHistoryStore(ABC):
    @abstractmethod
    def save(self, request_id: str, examples: List[AdversarialExample]) -> None:
        pass

    @abstractmethod
    def get(self, request_id: str) -> Optional[List[AdversarialExample]]:
        pass


class AssessmentCache(ABC):
    @abstractmethod
    def save(self, assessment_id: str, response: SecurityAssessmentResponse) -> None:
        pass

    @abstractmethod
    def get(self, assessment_id: str) -> Optional[SecurityAssessmentResponse]:
        pass
