from typing import List
from ..protocols import AttackResultAggregator
from ..schemas import AttackResult, SecurityMetrics


class DefaultAttackResultAggregator(AttackResultAggregator):
    def calculate_risk_score(self, results: List[AttackResult]) -> float:
        if not results:
            return 0.0

        total_confidence = sum(r.confidence for r in results if r.success)
        max_possible = len(results)
        return min(1.0, total_confidence / max_possible if max_possible > 0 else 0.0)

    def calculate_security_score(self, metrics: List[SecurityMetrics]) -> float:
        if not metrics:
            return 1.0

        total_success_rate = sum(m.success_rate for m in metrics)
        avg_success_rate = total_success_rate / len(metrics)
        return max(0.0, 1.0 - avg_success_rate)

    def determine_risk_level(self, score: float) -> str:
        if score >= 0.9:
            return "low"
        elif score >= 0.7:
            return "medium"
        elif score >= 0.5:
            return "high"
        else:
            return "critical"
