from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple
from dataclasses import dataclass, field
from enum import Enum
import numpy as np
import uuid

from app.core.logger import logger
from app.core.config import settings
from app.core.events import event_bus, EventType, build_event


class NoiseMechanism(str, Enum):
    LAPLACE = "laplace"
    GAUSSIAN = "gaussian"
    EXPONENTIAL = "exponential"


@dataclass
class PrivacyBudget:
    budget_id: str
    epsilon: float
    delta: float
    remaining_epsilon: float
    remaining_delta: float
    created_at: datetime = field(default_factory=datetime.utcnow)
    last_consumed_at: Optional[datetime] = None
    consumption_history: List[Dict[str, Any]] = field(default_factory=list)


@dataclass
class QueryResult:
    original_value: Any
    noisy_value: Any
    epsilon_used: float
    delta_used: float
    mechanism: NoiseMechanism
    sensitivity: float
    timestamp: datetime = field(default_factory=datetime.utcnow)


class NoiseInjector:
    def __init__(self, seed: Optional[int] = None):
        self._rng = np.random.default_rng(seed)
        logger.info("NoiseInjector initialized")

    def laplace_mechanism(self, value: float, sensitivity: float, epsilon: float) -> float:
        if epsilon <= 0:
            raise ValueError("Epsilon must be positive")
        scale = sensitivity / epsilon
        noise = self._rng.laplace(loc=0, scale=scale)
        return float(value + noise)

    def gaussian_mechanism(self, value: float, sensitivity: float,
                           epsilon: float, delta: float) -> float:
        if epsilon <= 0 or delta <= 0:
            raise ValueError("Epsilon and delta must be positive")
        sigma = sensitivity * np.sqrt(2 * np.log(1.25 / delta)) / epsilon
        noise = self._rng.normal(loc=0, scale=sigma)
        return float(value + noise)

    def exponential_mechanism(self, scores: List[float], sensitivity: float,
                               epsilon: float) -> int:
        if epsilon <= 0:
            raise ValueError("Epsilon must be positive")
        weights = np.exp((epsilon * np.array(scores)) / (2 * sensitivity))
        probabilities = weights / np.sum(weights)
        return int(self._rng.choice(len(scores), p=probabilities))

    def add_noise(self, value: Any, sensitivity: float, epsilon: float,
                  delta: float = 1e-5,
                  mechanism: NoiseMechanism = NoiseMechanism.LAPLACE) -> Tuple[Any, float, float]:
        if isinstance(value, (int, float)):
            if mechanism == NoiseMechanism.LAPLACE:
                noisy = self.laplace_mechanism(float(value), sensitivity, epsilon)
                return noisy, epsilon, 0.0
            elif mechanism == NoiseMechanism.GAUSSIAN:
                noisy = self.gaussian_mechanism(float(value), sensitivity, epsilon, delta)
                return noisy, epsilon, delta
            else:
                noisy = self.laplace_mechanism(float(value), sensitivity, epsilon)
                return noisy, epsilon, 0.0
        elif isinstance(value, list):
            noisy_list = []
            total_eps = 0.0
            total_delta = 0.0
            per_item_eps = epsilon / max(1, len(value))
            for item in value:
                if isinstance(item, (int, float)):
                    noisy, eps_used, delta_used = self.add_noise(
                        item, sensitivity, per_item_eps, delta, mechanism
                    )
                    noisy_list.append(noisy)
                    total_eps += eps_used
                    total_delta += delta_used
                else:
                    noisy_list.append(item)
            return noisy_list, total_eps, total_delta
        elif isinstance(value, dict):
            noisy_dict = {}
            total_eps = 0.0
            total_delta = 0.0
            keys = list(value.keys())
            per_key_eps = epsilon / max(1, len(keys))
            for key, item in value.items():
                if isinstance(item, (int, float)):
                    noisy, eps_used, delta_used = self.add_noise(
                        item, sensitivity, per_key_eps, delta, mechanism
                    )
                    noisy_dict[key] = noisy
                    total_eps += eps_used
                    total_delta += delta_used
                else:
                    noisy_dict[key] = item
            return noisy_dict, total_eps, total_delta
        else:
            return value, 0.0, 0.0

    def compute_sensitivity(self, data: List[float]) -> float:
        if len(data) < 2:
            return 0.0
        sorted_data = sorted(data)
        max_range = max(
            abs(sorted_data[-1] - sorted_data[0]),
            abs(sorted_data[-2] - sorted_data[0]) if len(sorted_data) > 2 else 0,
            abs(sorted_data[-1] - sorted_data[1]) if len(sorted_data) > 2 else 0
        )
        return float(max_range)

    def clip_value(self, value: float, lower: float, upper: float) -> float:
        return float(np.clip(value, lower, upper))


class PrivacyBudgetManager:
    def __init__(self, default_epsilon: Optional[float] = None,
                  default_delta: Optional[float] = None):
        self._default_epsilon = default_epsilon or settings.default_epsilon
        self._default_delta = default_delta or settings.default_delta
        self._max_budget = settings.max_privacy_budget
        self._budgets: Dict[str, PrivacyBudget] = {}
        self._global_budget = PrivacyBudget(
            budget_id="global",
            epsilon=self._max_budget,
            delta=self._default_delta,
            remaining_epsilon=self._max_budget,
            remaining_delta=self._default_delta
        )
        logger.info(f"PrivacyBudgetManager initialized with max_budget={self._max_budget}")

    def create_budget(self, budget_id: Optional[str] = None,
                       epsilon: Optional[float] = None,
                       delta: Optional[float] = None) -> PrivacyBudget:
        budget_id = budget_id or f"budget_{uuid.uuid4().hex[:8]}"
        budget = PrivacyBudget(
            budget_id=budget_id,
            epsilon=epsilon or self._default_epsilon,
            delta=delta or self._default_delta,
            remaining_epsilon=epsilon or self._default_epsilon,
            remaining_delta=delta or self._default_delta
        )
        self._budgets[budget_id] = budget
        logger.info(f"Created privacy budget: {budget_id}")
        return budget

    def get_budget(self, budget_id: str) -> Optional[PrivacyBudget]:
        return self._budgets.get(budget_id)

    def check_budget(self, budget_id: str, required_epsilon: float,
                      required_delta: float = 0.0) -> bool:
        budget = self._budgets.get(budget_id)
        if not budget:
            return False

        global_ok = (
            self._global_budget.remaining_epsilon >= required_epsilon and
            self._global_budget.remaining_delta >= required_delta
        )
        budget_ok = (
            budget.remaining_epsilon >= required_epsilon and
            budget.remaining_delta >= required_delta
        )
        return global_ok and budget_ok

    def consume_budget(self, budget_id: str, epsilon: float, delta: float = 0.0,
                        query_info: Optional[Dict[str, Any]] = None) -> bool:
        if not self.check_budget(budget_id, epsilon, delta):
            logger.warning(f"Insufficient privacy budget for {budget_id}")
            return False

        budget = self._budgets[budget_id]
        budget.remaining_epsilon -= epsilon
        budget.remaining_delta -= delta
        budget.last_consumed_at = datetime.utcnow()

        self._global_budget.remaining_epsilon -= epsilon
        self._global_budget.remaining_delta -= delta

        consumption = {
            "timestamp": datetime.utcnow().isoformat(),
            "epsilon": epsilon,
            "delta": delta,
            "query_info": query_info or {}
        }
        budget.consumption_history.append(consumption)
        self._global_budget.consumption_history.append(consumption)

        event_bus.emit(build_event(EventType.PRIVACY_BUDGET_CONSUMED, {
            "budget_id": budget_id,
            "epsilon": epsilon,
            "delta": delta,
            "remaining_epsilon": budget.remaining_epsilon
        }))

        logger.info(f"Consumed budget: {epsilon}/{delta} from {budget_id}")
        return True

    def reset_budget(self, budget_id: str) -> bool:
        budget = self._budgets.get(budget_id)
        if not budget:
            return False

        budget.remaining_epsilon = budget.epsilon
        budget.remaining_delta = budget.delta
        budget.last_consumed_at = None
        logger.info(f"Reset privacy budget: {budget_id}")
        return True

    def get_global_status(self) -> Dict[str, Any]:
        return {
            "budget_id": self._global_budget.budget_id,
            "max_epsilon": self._max_budget,
            "remaining_epsilon": self._global_budget.remaining_epsilon,
            "used_epsilon": self._max_budget - self._global_budget.remaining_epsilon,
            "utilization_rate": (self._max_budget - self._global_budget.remaining_epsilon) / self._max_budget,
            "consumption_count": len(self._global_budget.consumption_history)
        }

    def list_budgets(self) -> List[Dict[str, Any]]:
        return [
            {
                "budget_id": b.budget_id,
                "total_epsilon": b.epsilon,
                "remaining_epsilon": b.remaining_epsilon,
                "total_delta": b.delta,
                "remaining_delta": b.remaining_delta,
                "last_consumed_at": b.last_consumed_at.isoformat() if b.last_consumed_at else None,
                "consumption_count": len(b.consumption_history)
            }
            for b in self._budgets.values()
        ]


class DifferentialPrivacyModule:
    def __init__(self):
        self._noise_injector = NoiseInjector()
        self._budget_manager = PrivacyBudgetManager()
        self._results_cache: List[QueryResult] = []
        logger.info("DifferentialPrivacyModule initialized")

    @property
    def noise_injector(self) -> NoiseInjector:
        return self._noise_injector

    @property
    def budget_manager(self) -> PrivacyBudgetManager:
        return self._budget_manager

    def private_count(self, data: List[Any], budget_id: str,
                       epsilon: float = 1.0) -> Optional[int]:
        true_count = len(data)
        sensitivity = 1.0

        if not self._budget_manager.check_budget(budget_id, epsilon):
            logger.warning(f"Insufficient budget for count query")
            return None

        noisy_count = self._noise_injector.laplace_mechanism(
            float(true_count), sensitivity, epsilon
        )
        result = QueryResult(
            original_value=true_count,
            noisy_value=int(round(noisy_count)),
            epsilon_used=epsilon,
            delta_used=0.0,
            mechanism=NoiseMechanism.LAPLACE,
            sensitivity=sensitivity
        )
        self._results_cache.append(result)
        self._budget_manager.consume_budget(budget_id, epsilon, 0.0, {"type": "count"})

        return int(round(noisy_count))

    def private_sum(self, data: List[float], budget_id: str,
                     lower: float = 0.0, upper: float = 1.0,
                     epsilon: float = 1.0) -> Optional[float]:
        clipped_data = [self._noise_injector.clip_value(x, lower, upper) for x in data]
        true_sum = sum(clipped_data)
        sensitivity = upper - lower

        if not self._budget_manager.check_budget(budget_id, epsilon):
            logger.warning(f"Insufficient budget for sum query")
            return None

        noisy_sum = self._noise_injector.laplace_mechanism(
            float(true_sum), sensitivity, epsilon
        )
        result = QueryResult(
            original_value=true_sum,
            noisy_value=noisy_sum,
            epsilon_used=epsilon,
            delta_used=0.0,
            mechanism=NoiseMechanism.LAPLACE,
            sensitivity=sensitivity
        )
        self._results_cache.append(result)
        self._budget_manager.consume_budget(budget_id, epsilon, 0.0, {"type": "sum"})

        return noisy_sum

    def private_mean(self, data: List[float], budget_id: str,
                      lower: float = 0.0, upper: float = 1.0,
                      epsilon: float = 1.0) -> Optional[float]:
        if len(data) == 0:
            return 0.0

        count_eps = epsilon * 0.5
        sum_eps = epsilon * 0.5

        noisy_count = self.private_count(data, budget_id, count_eps)
        if noisy_count is None:
            return None

        noisy_sum = self.private_sum(data, budget_id, lower, upper, sum_eps)
        if noisy_sum is None:
            return None

        return noisy_sum / max(1, noisy_count)

    def private_query(self, query_result: Any, budget_id: str,
                       sensitivity: float = 1.0,
                       epsilon: float = 1.0,
                       delta: float = 1e-5,
                       mechanism: NoiseMechanism = NoiseMechanism.LAPLACE) -> Optional[Any]:
        if not self._budget_manager.check_budget(budget_id, epsilon, delta):
            logger.warning(f"Insufficient privacy budget")
            return None

        noisy_value, eps_used, delta_used = self._noise_injector.add_noise(
            query_result, sensitivity, epsilon, delta, mechanism
        )

        result = QueryResult(
            original_value=query_result,
            noisy_value=noisy_value,
            epsilon_used=eps_used,
            delta_used=delta_used,
            mechanism=mechanism,
            sensitivity=sensitivity
        )
        self._results_cache.append(result)

        self._budget_manager.consume_budget(budget_id, eps_used, delta_used, {
            "type": "custom_query",
            "mechanism": mechanism
        })

        return noisy_value

    def get_recent_results(self, limit: int = 10) -> List[QueryResult]:
        return self._results_cache[-limit:]

    def get_privacy_report(self, budget_id: Optional[str] = None) -> Dict[str, Any]:
        report = {
            "global_status": self._budget_manager.get_global_status(),
            "total_queries": len(self._results_cache),
            "timestamp": datetime.utcnow().isoformat()
        }

        if budget_id:
            budget = self._budget_manager.get_budget(budget_id)
            if budget:
                report["budget_details"] = {
                    "budget_id": budget.budget_id,
                    "total_epsilon": budget.epsilon,
                    "remaining_epsilon": budget.remaining_epsilon,
                    "total_delta": budget.delta,
                    "remaining_delta": budget.remaining_delta,
                    "consumption_history": budget.consumption_history
                }

        return report


dp_module = DifferentialPrivacyModule()
