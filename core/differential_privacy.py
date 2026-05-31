import math
import random
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple, Union
from pydantic import BaseModel, Field

from .config import settings
from .utils import generate_id


class PrivacyBudget(BaseModel):
    budget_id: str = Field(..., description="预算ID")
    user_id: str = Field(..., description="用户ID")
    total_epsilon: float = Field(..., description="总epsilon预算")
    remaining_epsilon: float = Field(..., description="剩余epsilon预算")
    total_delta: float = Field(default=1e-5, description="总delta预算")
    remaining_delta: float = Field(default=1e-5, description="剩余delta预算")
    created_at: str = Field(..., description="创建时间")
    last_used: Optional[str] = Field(None, description="最后使用时间")
    queries_count: int = Field(default=0, description="查询次数")


class QueryResult(BaseModel):
    query_id: str = Field(..., description="查询ID")
    original_result: Any = Field(..., description="原始结果")
    noisy_result: Any = Field(..., description="加噪结果")
    epsilon_used: float = Field(..., description="使用的epsilon")
    delta_used: float = Field(..., description="使用的delta")
    noise_added: float = Field(..., description="添加的噪声大小")
    mechanism: str = Field(..., description="使用的机制")
    timestamp: str = Field(..., description="时间戳")


class DPMechanism(BaseModel):
    name: str = Field(..., description="机制名称")
    description: str = Field(..., description="描述")
    epsilon: float = Field(..., description="epsilon参数")
    delta: Optional[float] = Field(None, description="delta参数")
    sensitivity: float = Field(..., description="敏感度")


class DifferentialPrivacyEngine:
    def __init__(self):
        self.budgets: Dict[str, PrivacyBudget] = {}
        self.query_history: Dict[str, List[QueryResult]] = {}
        self._default_epsilon = settings.privacy_budget_default

    def create_budget(
        self,
        user_id: str,
        total_epsilon: Optional[float] = None,
        total_delta: float = 1e-5
    ) -> PrivacyBudget:
        budget_id = generate_id("bgt_")
        total_epsilon = total_epsilon or self._default_epsilon

        budget = PrivacyBudget(
            budget_id=budget_id,
            user_id=user_id,
            total_epsilon=total_epsilon,
            remaining_epsilon=total_epsilon,
            total_delta=total_delta,
            remaining_delta=total_delta,
            created_at=datetime.utcnow().isoformat()
        )

        self.budgets[budget_id] = budget
        self.query_history[budget_id] = []

        return budget

    def get_budget(self, budget_id: str) -> Optional[PrivacyBudget]:
        return self.budgets.get(budget_id)

    def get_budgets_by_user(self, user_id: str) -> List[PrivacyBudget]:
        return [b for b in self.budgets.values() if b.user_id == user_id]

    def check_budget(self, budget_id: str, epsilon: float, delta: float = 0) -> bool:
        budget = self.budgets.get(budget_id)
        if not budget:
            return False
        return budget.remaining_epsilon >= epsilon and budget.remaining_delta >= delta

    def _consume_budget(self, budget_id: str, epsilon: float, delta: float) -> bool:
        budget = self.budgets.get(budget_id)
        if not budget:
            return False
        if budget.remaining_epsilon < epsilon or budget.remaining_delta < delta:
            return False

        budget.remaining_epsilon -= epsilon
        budget.remaining_delta -= delta
        budget.last_used = datetime.utcnow().isoformat()
        budget.queries_count += 1
        return True

    def _laplace_noise(self, scale: float, size: int = 1) -> Union[float, List[float]]:
        if size == 1:
            u = random.uniform(-0.5, 0.5)
            return -scale * math.copysign(1, u) * math.log(1 - 2 * abs(u))
        else:
            return [self._laplace_noise(scale) for _ in range(size)]

    def _gaussian_noise(self, sigma: float, size: int = 1) -> Union[float, List[float]]:
        if size == 1:
            u1 = random.random()
            u2 = random.random()
            z0 = math.sqrt(-2 * math.log(u1)) * math.cos(2 * math.pi * u2)
            return sigma * z0
        else:
            return [self._gaussian_noise(sigma) for _ in range(size)]

    def _exponential_mechanism(
        self,
        values: List[Any],
        utility_function,
        epsilon: float,
        sensitivity: float
    ) -> Any:
        if not values:
            return None

        utilities = [utility_function(v) for v in values]
        max_utility = max(utilities) if utilities else 0

        weights = [math.exp((epsilon * (u - max_utility)) / (2 * sensitivity)) for u in utilities]
        total_weight = sum(weights)

        if total_weight == 0:
            return random.choice(values)

        probabilities = [w / total_weight for w in weights]
        r = random.random()
        cumulative = 0.0
        for i, p in enumerate(probabilities):
            cumulative += p
            if r <= cumulative:
                return values[i]

        return values[-1]

    def laplace_mechanism(
        self,
        value: float,
        epsilon: float,
        sensitivity: float,
        budget_id: Optional[str] = None
    ) -> Tuple[float, float]:
        if budget_id and not self._consume_budget(budget_id, epsilon, 0):
            raise ValueError("Insufficient privacy budget")

        scale = sensitivity / epsilon
        noise = self._laplace_noise(scale)
        noisy_value = value + noise

        return noisy_value, noise

    def gaussian_mechanism(
        self,
        value: float,
        epsilon: float,
        delta: float,
        sensitivity: float,
        budget_id: Optional[str] = None
    ) -> Tuple[float, float]:
        if epsilon >= 1:
            raise ValueError("Gaussian mechanism requires epsilon < 1")

        if budget_id and not self._consume_budget(budget_id, epsilon, delta):
            raise ValueError("Insufficient privacy budget")

        sigma = sensitivity * math.sqrt(2 * math.log(1.25 / delta)) / epsilon
        noise = self._gaussian_noise(sigma)
        noisy_value = value + noise

        return noisy_value, noise

    def add_noise_to_numeric(
        self,
        value: float,
        epsilon: float,
        sensitivity: float = 1.0,
        mechanism: str = "laplace",
        delta: float = 1e-5,
        budget_id: Optional[str] = None
    ) -> Dict[str, Any]:
        if mechanism == "laplace":
            noisy, noise = self.laplace_mechanism(value, epsilon, sensitivity, budget_id)
        elif mechanism == "gaussian":
            noisy, noise = self.gaussian_mechanism(value, epsilon, delta, sensitivity, budget_id)
        else:
            raise ValueError(f"Unknown mechanism: {mechanism}")

        query_id = generate_id("qry_")
        result = QueryResult(
            query_id=query_id,
            original_result=value,
            noisy_result=noisy,
            epsilon_used=epsilon,
            delta_used=delta if mechanism == "gaussian" else 0,
            noise_added=noise,
            mechanism=mechanism,
            timestamp=datetime.utcnow().isoformat()
        )

        if budget_id:
            self.query_history[budget_id].append(result)

        return result.dict()

    def add_noise_to_list(
        self,
        values: List[float],
        epsilon: float,
        sensitivity: float = 1.0,
        mechanism: str = "laplace",
        delta: float = 1e-5,
        budget_id: Optional[str] = None
    ) -> Dict[str, Any]:
        epsilon_per_value = epsilon / len(values) if values else epsilon

        noisy_values = []
        total_noise = 0.0

        for value in values:
            if mechanism == "laplace":
                scale = sensitivity / epsilon_per_value
                noise = self._laplace_noise(scale)
                noisy_values.append(value + noise)
                total_noise += noise
            elif mechanism == "gaussian":
                sigma = sensitivity * math.sqrt(2 * math.log(1.25 / delta)) / epsilon_per_value
                noise = self._gaussian_noise(sigma)
                noisy_values.append(value + noise)
                total_noise += noise

        if budget_id:
            self._consume_budget(budget_id, epsilon, delta if mechanism == "gaussian" else 0)

            query_id = generate_id("qry_")
            result = QueryResult(
                query_id=query_id,
                original_result=values,
                noisy_result=noisy_values,
                epsilon_used=epsilon,
                delta_used=delta if mechanism == "gaussian" else 0,
                noise_added=total_noise / len(values) if values else 0,
                mechanism=mechanism,
                timestamp=datetime.utcnow().isoformat()
            )
            self.query_history[budget_id].append(result)
            return result.dict()

        return {
            "original_values": values,
            "noisy_values": noisy_values,
            "epsilon_used": epsilon,
            "mechanism": mechanism
        }

    def add_noise_to_dict(
        self,
        data: Dict[str, Any],
        epsilon: float,
        numeric_fields: List[str],
        sensitivity: float = 1.0,
        mechanism: str = "laplace",
        budget_id: Optional[str] = None
    ) -> Dict[str, Any]:
        result = dict(data)
        epsilon_per_field = epsilon / len(numeric_fields) if numeric_fields else epsilon

        for field in numeric_fields:
            if field in data and isinstance(data[field], (int, float)):
                if mechanism == "laplace":
                    scale = sensitivity / epsilon_per_field
                    noise = self._laplace_noise(scale)
                    result[field] = data[field] + noise
                elif mechanism == "gaussian":
                    delta = 1e-5
                    sigma = sensitivity * math.sqrt(2 * math.log(1.25 / delta)) / epsilon_per_field
                    noise = self._gaussian_noise(sigma)
                    result[field] = data[field] + noise

        if budget_id:
            self._consume_budget(budget_id, epsilon, 0)

        return result

    def private_count(
        self,
        data: List[Any],
        epsilon: float,
        budget_id: Optional[str] = None
    ) -> Dict[str, Any]:
        true_count = len(data)
        noisy_count, noise = self.laplace_mechanism(
            float(true_count), epsilon, 1.0, budget_id
        )

        return {
            "true_count": true_count,
            "noisy_count": max(0, round(noisy_count)),
            "epsilon_used": epsilon,
            "noise_added": noise
        }

    def private_sum(
        self,
        values: List[float],
        epsilon: float,
        lower_bound: float,
        upper_bound: float,
        budget_id: Optional[str] = None
    ) -> Dict[str, Any]:
        sensitivity = upper_bound - lower_bound
        clamped_values = [max(lower_bound, min(upper_bound, v)) for v in values]
        true_sum = sum(clamped_values)

        noisy_sum, noise = self.laplace_mechanism(
            true_sum, epsilon, sensitivity, budget_id
        )

        return {
            "true_sum": true_sum,
            "noisy_sum": noisy_sum,
            "epsilon_used": epsilon,
            "sensitivity": sensitivity,
            "noise_added": noise
        }

    def private_average(
        self,
        values: List[float],
        epsilon: float,
        lower_bound: float,
        upper_bound: float,
        budget_id: Optional[str] = None
    ) -> Dict[str, Any]:
        count_epsilon = epsilon * 0.5
        sum_epsilon = epsilon * 0.5

        true_count = len(values)
        noisy_count = max(1, round(self.laplace_mechanism(float(true_count), count_epsilon, 1.0)[0]))

        clamped_values = [max(lower_bound, min(upper_bound, v)) for v in values]
        true_sum = sum(clamped_values)
        sensitivity = upper_bound - lower_bound
        noisy_sum = self.laplace_mechanism(true_sum, sum_epsilon, sensitivity)[0]

        noisy_avg = noisy_sum / noisy_count
        true_avg = true_sum / true_count if true_count > 0 else 0

        if budget_id:
            self._consume_budget(budget_id, epsilon, 0)

        return {
            "true_average": true_avg,
            "noisy_average": noisy_avg,
            "noisy_count": noisy_count,
            "noisy_sum": noisy_sum,
            "epsilon_used": epsilon
        }

    def compose_mechanisms(
        self,
        mechanisms: List[Dict[str, Any]],
        budget_id: Optional[str] = None
    ) -> Dict[str, Any]:
        total_epsilon = sum(m["epsilon"] for m in mechanisms)
        total_delta = sum(m.get("delta", 0) for m in mechanisms)

        if budget_id and not self.check_budget(budget_id, total_epsilon, total_delta):
            raise ValueError("Insufficient privacy budget for composition")

        advanced_composition_epsilon = 2 * math.sqrt(2 * len(mechanisms) * math.log(1 / (total_delta * 2))) * total_epsilon
        advanced_composition_delta = 2 * total_delta

        return {
            "basic_composition": {
                "epsilon": total_epsilon,
                "delta": total_delta
            },
            "advanced_composition": {
                "epsilon": advanced_composition_epsilon,
                "delta": advanced_composition_delta
            },
            "mechanisms_count": len(mechanisms)
        }

    def get_query_history(self, budget_id: str) -> List[QueryResult]:
        return self.query_history.get(budget_id, [])

    def reset_budget(self, budget_id: str) -> bool:
        budget = self.budgets.get(budget_id)
        if not budget:
            return False

        budget.remaining_epsilon = budget.total_epsilon
        budget.remaining_delta = budget.total_delta
        budget.queries_count = 0
        budget.last_used = None

        return True

    def delete_budget(self, budget_id: str) -> bool:
        if budget_id in self.budgets:
            del self.budgets[budget_id]
            if budget_id in self.query_history:
                del self.query_history[budget_id]
            return True
        return False

    def get_statistics(self) -> Dict[str, Any]:
        total_budgets = len(self.budgets)
        total_queries = sum(len(h) for h in self.query_history.values())
        avg_remaining = sum(b.remaining_epsilon for b in self.budgets.values()) / total_budgets if total_budgets > 0 else 0

        return {
            "total_budgets": total_budgets,
            "total_queries": total_queries,
            "average_remaining_epsilon": avg_remaining,
            "default_epsilon": self._default_epsilon
        }


_dp_engine_instance: Optional[DifferentialPrivacyEngine] = None


def get_dp_engine() -> DifferentialPrivacyEngine:
    global _dp_engine_instance
    if _dp_engine_instance is None:
        _dp_engine_instance = DifferentialPrivacyEngine()
    return _dp_engine_instance
