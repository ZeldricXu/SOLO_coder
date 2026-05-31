import asyncio
import logging
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple
from datetime import datetime, timezone, timedelta
from enum import Enum

from wallethub.core import GasEstimationError
from wallethub.config import get_settings
from wallethub.modules.chain_adapter import ChainClient
from wallethub.utils import generate_id

logger = logging.getLogger(__name__)


class GasSpeed(str, Enum):
    SLOW = "slow"
    STANDARD = "standard"
    FAST = "fast"
    URGENT = "urgent"


@dataclass
class GasEstimate:
    estimate_id: str = field(default_factory=lambda: generate_id("gas"))
    chain: str = ""
    timestamp: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    block_number: int = 0
    base_fee: Optional[int] = None
    slow: Dict[str, Optional[int]] = field(default_factory=dict)
    standard: Dict[str, Optional[int]] = field(default_factory=dict)
    fast: Dict[str, Optional[int]] = field(default_factory=dict)
    urgent: Dict[str, Optional[int]] = field(default_factory=dict)
    historical_data: Dict[str, Any] = field(default_factory=dict)


class GasPriceOracle:
    def __init__(self, chain_client: ChainClient):
        self.chain_client = chain_client
        self._history: List[Tuple[int, int, int, List[int]]] = []
        self._max_history = 1000

    async def fetch_current_fees(self) -> Dict[str, Any]:
        try:
            block_number = await self.chain_client.get_block_number()
            block = await self.chain_client.get_block(block_number)

            base_fee = block.get("baseFeePerGas")
            gas_price = await self.chain_client.get_gas_price()
            priority_fee = await self.chain_client.get_priority_fee()

            fee_history = await self.chain_client.get_fee_history(
                block_count=20,
                newest_block="latest",
                reward_percentiles=[25, 50, 75, 90],
            )

            return {
                "block_number": block_number,
                "base_fee": int(base_fee) if base_fee else None,
                "gas_price": int(gas_price),
                "priority_fee": int(priority_fee),
                "fee_history": fee_history,
            }
        except Exception as e:
            raise GasEstimationError(f"Failed to fetch current fees: {str(e)}")

    async def estimate_gas_price(
        self,
        speed: GasSpeed = GasSpeed.STANDARD,
    ) -> int:
        fees = await self.fetch_current_fees()
        base_fee = fees.get("base_fee")

        if base_fee is None:
            gas_price = fees.get("gas_price", 0)
            multipliers = {
                GasSpeed.SLOW: 0.9,
                GasSpeed.STANDARD: 1.0,
                GasSpeed.FAST: 1.2,
                GasSpeed.URGENT: 1.5,
            }
            return int(gas_price * multipliers[speed])

        reward_percentiles = fees["fee_history"].get("reward", [])
        if not reward_percentiles:
            priority_fee = fees.get("priority_fee", 0)
        else:
            idx = {
                GasSpeed.SLOW: 0,
                GasSpeed.STANDARD: 1,
                GasSpeed.FAST: 2,
                GasSpeed.URGENT: 3,
            }.get(speed, 1)
            all_priorities = [p for row in reward_percentiles for p in row if p > 0]
            if all_priorities:
                all_priorities.sort()
                pos = min(idx * len(all_priorities) // 3, len(all_priorities) - 1)
                priority_fee = all_priorities[pos]
            else:
                priority_fee = fees.get("priority_fee", 0)

        max_priority_fee = int(priority_fee * {
            GasSpeed.SLOW: 0.8,
            GasSpeed.STANDARD: 1.0,
            GasSpeed.FAST: 1.3,
            GasSpeed.URGENT: 2.0,
        }[speed])

        max_fee = int(base_fee * 2 + max_priority_fee)

        self._history.append((
            fees["block_number"],
            base_fee,
            max_priority_fee,
            reward_percentiles[-1] if reward_percentiles else [],
        ))

        if len(self._history) > self._max_history:
            self._history = self._history[-self._max_history:]

        return max_fee


class GasEstimator:
    def __init__(self, chain_client: ChainClient):
        self.settings = get_settings()
        self.chain_client = chain_client
        self.oracle = GasPriceOracle(chain_client)
        self._estimates: Dict[str, GasEstimate] = {}
        self._auto_update = False
        self._update_task: Optional[asyncio.Task] = None
        self._update_interval = 15

    async def estimate_all(self) -> GasEstimate:
        try:
            fees = await self.oracle.fetch_current_fees()
            block_number = fees["block_number"]
            base_fee = fees["base_fee"]
            fee_history = fees["fee_history"]

            reward_percentiles = fee_history.get("reward", [])
            avg_rewards = self._calculate_average_rewards(reward_percentiles)

            def get_estimate(speed_idx: int, base_multiplier: float, priority_multiplier: float) -> Dict[str, Optional[int]]:
                if base_fee is None:
                    legacy_price = int(fees.get("gas_price", 0) * base_multiplier)
                    return {
                        "gas_price": legacy_price,
                        "max_fee_per_gas": None,
                        "max_priority_fee_per_gas": None,
                    }
                else:
                    priority_fee = int(avg_rewards[speed_idx] * priority_multiplier) if len(avg_rewards) > speed_idx else int(fees.get("priority_fee", 0) * priority_multiplier)
                    max_fee = int(base_fee * 2 + priority_fee)
                    return {
                        "gas_price": None,
                        "max_fee_per_gas": max_fee,
                        "max_priority_fee_per_gas": priority_fee,
                    }

            estimate = GasEstimate(
                chain=self.chain_client.chain,
                block_number=block_number,
                base_fee=base_fee,
                slow=get_estimate(0, 0.9, 0.7),
                standard=get_estimate(1, 1.0, 1.0),
                fast=get_estimate(2, 1.2, 1.5),
                urgent=get_estimate(3, 1.5, 2.5),
                historical_data={
                    "base_fee_trend": self._calculate_trend(
                        [h[1] for h in self.oracle._history[-20:]]
                    ),
                    "volatility": self._calculate_volatility(
                        [h[1] for h in self.oracle._history[-20:]]
                    ),
                },
            )

            self._estimates[estimate.estimate_id] = estimate
            return estimate

        except Exception as e:
            raise GasEstimationError(f"Failed to estimate gas: {str(e)}")

    async def estimate_for_transaction(
        self,
        tx_params: Dict[str, Any],
        speed: GasSpeed = GasSpeed.STANDARD,
    ) -> Dict[str, Any]:
        estimate = await self.estimate_all()

        speed_estimates = {
            GasSpeed.SLOW: estimate.slow,
            GasSpeed.STANDARD: estimate.standard,
            GasSpeed.FAST: estimate.fast,
            GasSpeed.URGENT: estimate.urgent,
        }[speed]

        try:
            gas_limit = await self.chain_client.estimate_gas(tx_params)
        except Exception:
            gas_limit = 21000

        buffer = 1.1
        estimated_gas_limit = int(gas_limit * buffer)

        if speed_estimates.get("max_fee_per_gas"):
            estimated_cost = estimated_gas_limit * speed_estimates["max_fee_per_gas"]
        else:
            estimated_cost = estimated_gas_limit * (speed_estimates.get("gas_price") or 0)

        return {
            "gas_limit": estimated_gas_limit,
            **speed_estimates,
            "estimated_cost": estimated_cost,
            "base_fee": estimate.base_fee,
            "block_number": estimate.block_number,
        }

    def get_recent_estimates(self, limit: int = 10) -> List[GasEstimate]:
        return sorted(
            self._estimates.values(),
            key=lambda e: e.timestamp,
            reverse=True,
        )[:limit]

    async def start_auto_update(self) -> None:
        if self._auto_update:
            return

        self._auto_update = True
        self._update_task = asyncio.create_task(self._auto_update_loop())
        logger.info(f"Started auto gas price update for {self.chain_client.chain}")

    async def stop_auto_update(self) -> None:
        self._auto_update = False
        if self._update_task:
            self._update_task.cancel()
            try:
                await self._update_task
            except asyncio.CancelledError:
                pass
        logger.info(f"Stopped auto gas price update for {self.chain_client.chain}")

    async def _auto_update_loop(self) -> None:
        while self._auto_update:
            try:
                await self.estimate_all()
            except Exception as e:
                logger.error(f"Auto gas update failed: {str(e)}")
            await asyncio.sleep(self._update_interval)

    @staticmethod
    def _calculate_average_rewards(reward_percentiles: List[List[int]]) -> List[int]:
        if not reward_percentiles:
            return [0, 0, 0, 0]

        num_percentiles = len(reward_percentiles[0]) if reward_percentiles else 4
        averages = []
        for i in range(num_percentiles):
            values = [row[i] for row in reward_percentiles if len(row) > i and row[i] > 0]
            if values:
                averages.append(sum(values) // len(values))
            else:
                averages.append(0)

        while len(averages) < 4:
            averages.append(averages[-1] if averages else 0)

        return averages

    @staticmethod
    def _calculate_trend(values: List[int]) -> str:
        if len(values) < 2:
            return "stable"

        first_half = values[:len(values) // 2]
        second_half = values[len(values) // 2:]

        if not first_half or not second_half:
            return "stable"

        avg_first = sum(first_half) / len(first_half)
        avg_second = sum(second_half) / len(second_half)

        change = (avg_second - avg_first) / avg_first if avg_first > 0 else 0

        if change > 0.1:
            return "increasing"
        elif change < -0.1:
            return "decreasing"
        else:
            return "stable"

    @staticmethod
    def _calculate_volatility(values: List[int]) -> float:
        if len(values) < 2:
            return 0.0

        avg = sum(values) / len(values)
        if avg == 0:
            return 0.0

        variance = sum((v - avg) ** 2 for v in values) / len(values)
        std_dev = variance ** 0.5
        return std_dev / avg
