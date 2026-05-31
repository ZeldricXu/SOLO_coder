from typing import List
from ..protocols import RecommendationEngine
from ..schemas import AttackResult, SecurityMetrics


class DefaultRecommendationEngine(RecommendationEngine):
    def generate_attack_recommendations(
        self, results: List[AttackResult], risk_score: float
    ) -> List[str]:
        recommendations = []

        successful_strategies = [
            r.strategy.value for r in results if r.success and r.confidence > 0.7
        ]

        if successful_strategies:
            recommendations.append(
                f"High-risk attack strategies detected: {', '.join(successful_strategies)}"
            )
            recommendations.append(
                "Implement input sanitization and validation for the detected attack patterns"
            )

        if risk_score > 0.7:
            recommendations.append("CRITICAL: Immediate action required to harden the system")
            recommendations.append("Consider implementing a web application firewall (WAF)")
        elif risk_score > 0.4:
            recommendations.append("Moderate risk detected. Review and strengthen input validation")

        recommendations.append("Regular security assessments are recommended")
        recommendations.append("Monitor for unusual input patterns in production")

        return recommendations

    def generate_security_recommendations(
        self, metrics: List[SecurityMetrics], risk_level: str
    ) -> List[str]:
        recommendations = []

        high_risk_categories = [
            m.category for m in metrics if m.success_rate > 0.5
        ]

        if high_risk_categories:
            recommendations.append(
                f"High failure rate in categories: {', '.join(high_risk_categories)}"
            )

        if risk_level == "critical":
            recommendations.append("URGENT: Deploy emergency security patches immediately")
            recommendations.append("Consider temporarily restricting access until fixes are deployed")
        elif risk_level == "high":
            recommendations.append("Prioritize security fixes for the affected categories")
            recommendations.append("Enhance monitoring and logging for suspicious activity")
        elif risk_level == "medium":
            recommendations.append("Schedule security improvements in the next development cycle")
            recommendations.append("Review and update content moderation policies")

        recommendations.append("Implement continuous adversarial testing in CI/CD pipeline")
        recommendations.append("Subscribe to security bulletins for the model providers")

        return recommendations
