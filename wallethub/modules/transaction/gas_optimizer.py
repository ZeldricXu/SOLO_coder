from dataclasses import dataclass
from typing import Any, Dict, List, Optional, Tuple
from enum import Enum


class GasSpeed(str, Enum):
    SLOW = "slow"
    STANDARD = "standard"
    FAST = "fast"
    URGENT = "urgent"


@dataclass
class GasOptimizationResult:
    speed: GasSpeed
    gas_price: Optional[int] = None
    max_fee_per_gas: Optional[int] = None
    max_priority_fee_per_gas: Optional[int] = None
    estimated_gas: int = 0
    estimated_cost: int = 0
    savings_percent: float = 0.0
    recommended: bool = False


class GasOptimizer:
    def __init__(self, history_window: int = 100):
        self.history_window = history_window
        self._price_history: Dict[str, List[Tuple[int, int, int, int]]] = {}

    def record_block_prices(
        self,
        chain: str,
        block_number: int,
        base_fee: int,
        priority_fee_low: int,
        priority_fee_med: int,
        priority_fee_high: int,
    ) -> None:
        if chain not in self._price_history:
            self._price_history[chain] = []

        self._price_history[chain].append(
            (block_number, base_fee, priority_fee_low, priority_fee_high)
        )

        if len(self._price_history[chain]) > self.history_window:
            self._price_history[chain] = self._price_history[chain][-self.history_window:]

    def optimize_gas_fees(
        self,
        chain: str,
        current_base_fee: int,
        current_priority_fees: Dict[GasSpeed, int],
        estimated_gas: int,
        urgency: GasSpeed = GasSpeed.STANDARD,
        max_acceptable_fee: Optional[int] = None,
    ) -> GasOptimizationResult:
        history = self._price_history.get(chain, [])
        avg_base_fee = current_base_fee
        volatility = 0.0

        if len(history) >= 10:
            base_fees = [h[1] for h in history]
            avg_base_fee = sum(base_fees) / len(base_fees)
            variance = sum((bf - avg_base_fee) ** 2 for bf in base_fees) / len(base_fees)
            volatility = (variance ** 0.5) / avg_base_fee if avg_base_fee > 0 else 0

        buffer_multiplier = 1.0
        if urgency == GasSpeed.SLOW:
            buffer_multiplier = 0.9
        elif urgency == GasSpeed.STANDARD:
            buffer_multiplier = 1.0
        elif urgency == GasSpeed.FAST:
            buffer_multiplier = 1.1
        elif urgency == GasSpeed.URGENT:
            buffer_multiplier = 1.25

        volatility_buffer = min(volatility * 2, 0.3)
        total_multiplier = buffer_multiplier * (1 + volatility_buffer)

        recommended_priority_fee = current_priority_fees.get(urgency, current_priority_fees[GasSpeed.STANDARD])
        recommended_max_priority = int(recommended_priority_fee * total_multiplier)
        recommended_max_fee = int(current_base_fee * 2 + recommended_max_priority)

        if max_acceptable_fee and recommended_max_fee > max_acceptable_fee:
            recommended_max_fee = max_acceptable_fee
            recommended_max_priority = min(recommended_max_priority, max_acceptable_fee)

        estimated_cost = recommended_max_fee * estimated_gas

        savings = 0.0
        if urgency != GasSpeed.URGENT and len(history) >= 10:
            highest_fee = max(h[1] + h[3] for h in history)
            if highest_fee > 0:
                savings = (highest_fee - recommended_max_fee) / highest_fee * 100

        return GasOptimizationResult(
            speed=urgency,
            max_fee_per_gas=recommended_max_fee,
            max_priority_fee_per_gas=recommended_max_priority,
            estimated_gas=estimated_gas,
            estimated_cost=estimated_cost,
            savings_percent=savings,
            recommended=True,
        )

    def generate_all_options(
        self,
        chain: str,
        current_base_fee: int,
        current_priority_fees: Dict[GasSpeed, int],
        estimated_gas: int,
    ) -> List[GasOptimizationResult]:
        results = []
        for speed in GasSpeed:
            result = self.optimize_gas_fees(
                chain,
                current_base_fee,
                current_priority_fees,
                estimated_gas,
                urgency=speed,
            )
            result.recommended = (speed == GasSpeed.STANDARD)
            results.append(result)
        return results

    def suggest_gas_limit(
        self,
        estimated_gas: int,
        tx_type: str = "transfer",
    ) -> int:
        buffers = {
            "transfer": 1.0,
            "erc20_transfer": 1.1,
            "contract_call": 1.2,
            "contract_deployment": 1.5,
            "swap": 1.3,
        }
        buffer = buffers.get(tx_type, 1.2)
        return int(estimated_gas * buffer)

    @staticmethod
    def calculate_fee(
        gas_limit: int,
        gas_price: Optional[int] = None,
        max_fee_per_gas: Optional[int] = None,
    ) -> int:
        if max_fee_per_gas is not None:
            return gas_limit * max_fee_per_gas
        if gas_price is not None:
            return gas_limit * gas_price
        raise ValueError("Either gas_price or max_fee_per_gas must be provided")

    def get_volatility(self, chain: str) -> float:
        history = self._price_history.get(chain, [])
        if len(history) < 10:
            return 0.0
        base_fees = [h[1] for h in history]
        avg = sum(base_fees) / len(base_fees)
        variance = sum((bf - avg) ** 2 for bf in base_fees) / len(base_fees)
        return (variance ** 0.5) / avg if avg > 0 else 0
