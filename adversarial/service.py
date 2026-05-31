from typing import Optional
import time
from datetime import datetime, timezone

from .schemas import (
    AdversarialAttackRequest,
    AdversarialAttackResponse,
    SecurityAssessmentRequest,
    SecurityAssessmentResponse,
    AdversarialExample,
)
from .protocols import (
    AttackGenerator,
    SecurityAssessor,
    AttackResultAggregator,
    RecommendationEngine,
    AttackHistoryStore,
    AssessmentCache,
)
from .impl import (
    ParallelAttackGenerator,
    DefaultSecurityAssessor,
    DefaultAttackResultAggregator,
    DefaultRecommendationEngine,
    InMemoryAttackHistoryStore,
    InMemoryAssessmentCache,
)
from common.logger import get_logger

logger = get_logger(__name__)


class AdversarialService:
    def __init__(
        self,
        attack_generator: Optional[AttackGenerator] = None,
        security_assessor: Optional[SecurityAssessor] = None,
        result_aggregator: Optional[AttackResultAggregator] = None,
        recommendation_engine: Optional[RecommendationEngine] = None,
        history_store: Optional[AttackHistoryStore] = None,
        assessment_cache: Optional[AssessmentCache] = None,
    ):
        self.attack_generator = attack_generator or ParallelAttackGenerator()
        self.security_assessor = security_assessor or DefaultSecurityAssessor()
        self.result_aggregator = result_aggregator or DefaultAttackResultAggregator()
        self.recommendation_engine = recommendation_engine or DefaultRecommendationEngine()
        self.history_store = history_store or InMemoryAttackHistoryStore()
        self.assessment_cache = assessment_cache or InMemoryAssessmentCache()

    async def generate_adversarial_examples(
        self, request: AdversarialAttackRequest
    ) -> AdversarialAttackResponse:
        results, all_examples, request_id, start_time = await self.attack_generator.generate(request)

        overall_risk = self.result_aggregator.calculate_risk_score(results)
        recommendations = self.recommendation_engine.generate_attack_recommendations(
            results, overall_risk
        )

        self.history_store.save(request_id, all_examples)

        return AdversarialAttackResponse(
            request_id=request_id,
            target_prompt=request.target_prompt,
            results=results,
            overall_risk_score=overall_risk,
            total_attack_duration_ms=(time.time() - start_time) * 1000,
            recommendations=recommendations,
        )

    async def run_security_assessment(
        self, request: SecurityAssessmentRequest
    ) -> SecurityAssessmentResponse:
        assessment_data = await self.security_assessor.assess(request)

        assessment_id = assessment_data["assessment_id"]
        metrics = assessment_data["metrics"]
        high_risk_examples = assessment_data["high_risk_examples"]
        started_at = assessment_data["started_at"]

        overall_score = self.result_aggregator.calculate_security_score(metrics)
        risk_level = self.result_aggregator.determine_risk_level(overall_score)
        recommendations = self.recommendation_engine.generate_security_recommendations(
            metrics, risk_level
        )

        completed_at = datetime.now(timezone.utc)
        duration = (completed_at - started_at).total_seconds()

        response = SecurityAssessmentResponse(
            assessment_id=assessment_id,
            model_provider=request.model_provider,
            model_name=request.model_name,
            overall_security_score=overall_score,
            risk_level=risk_level,
            metrics=metrics,
            high_risk_examples=high_risk_examples[:20],
            recommendations=recommendations,
            started_at=started_at,
            completed_at=completed_at,
            duration_seconds=duration,
        )

        self.assessment_cache.save(assessment_id, response)
        logger.info(
            f"Security assessment {assessment_id} completed. Score: {overall_score:.2f}, Risk: {risk_level}"
        )

        return response

    def get_attack_history(self, request_id: str) -> Optional[list[AdversarialExample]]:
        return self.history_store.get(request_id)

    def get_assessment(self, assessment_id: str) -> Optional[SecurityAssessmentResponse]:
        return self.assessment_cache.get(assessment_id)


adversarial_service = AdversarialService()
