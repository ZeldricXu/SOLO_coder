from __future__ import annotations

import asyncio
import statistics
from typing import Any, Dict, List, Optional

from src.core.ports.chain_interaction_port import IChainInteractionPort
from src.core.ports.gas_estimator_port import IGasEstimatorPort
from src.shared.config import settings
from src.shared.errors import GasEstimationError
from src.shared.logger import get_logger
from src.shared.types import Address, GasAmount, GasEstimate, HexString, WeiAmount

logger = get_logger(__name__)


class GasEstimatorService(IGasEstimatorPort):
    def __init__(
        self,
        chain_adapter: IChainInteractionPort,
        history_blocks: Optional[int] = None,
        priority_fee_percentile: Optional[int] = None,
        max_priority_fee: Optional[WeiAmount] = None,
        gas_price_multiplier: Optional[float] = None,
    ):
        self._chain = chain_adapter
        self._history_blocks = history_blocks or settings.gas_estimator.history_blocks
        self._priority_fee_percentile = (
            priority_fee_percentile or settings.gas_estimator.priority_fee_percentile
        )
        self._max_priority_fee = max_priority_fee or settings.gas_estimator.max_priority_fee
        self._gas_price_multiplier = (
            gas_price_multiplier or settings.gas_estimator.gas_price_multiplier
        )
        self._price_cache: Dict[str, Any] = {}
        self._cache_timestamp = 0.0

    async def _get_fee_history(self) -> Dict[str, Any]:
        import time

        now = time.time()
        if now - self._cache_timestamp < 10 and self._price_cache:
            return self._price_cache

        try:
            reward_percentiles = [10, 20, 30, 50, 60, 70, 80, 90, 95]
            fee_history = await self._chain._safe_call(
                "fee_history",
                self._history_blocks,
                "latest",
                reward_percentiles,
            )

            self._price_cache = {
                "base_fee_per_gas": [int(x) for x in fee_history.get("baseFeePerGas", [])],
                "gas_used_ratio": [float(x) for x in fee_history.get("gasUsedRatio", [])],
                "reward": [
                    [int(x) for x in block] for block in fee_history.get("reward", [])
                ],
                "percentiles": reward_percentiles,
                "oldest_block": int(fee_history.get("oldestBlock", 0)),
            }
            self._cache_timestamp = now
            return self._price_cache
        except Exception as e:
            raise GasEstimationError(f"Failed to fetch fee history: {e}")

    async def estimate_gas_price(self, speed: str = "average") -> WeiAmount:
        speed_multipliers = {
            "slow": 0.9,
            "average": 1.0,
            "fast": 1.2,
            "rapid": 1.5,
        }
        multiplier = speed_multipliers.get(speed, 1.0)

        try:
            current_gas_price = await self._chain.get_gas_price()
            return int(current_gas_price * multiplier * self._gas_price_multiplier)
        except Exception as e:
            raise GasEstimationError(f"Failed to estimate gas price: {e}")

    async def estimate_eip1559_fees(self, speed: str = "average") -> Dict[str, WeiAmount]:
        speed_config = {
            "slow": {"priority_percentile": 0, "multiplier": 1.0},
            "average": {"priority_percentile": 4, "multiplier": 1.0},
            "fast": {"priority_percentile": 6, "multiplier": 1.2},
            "rapid": {"priority_percentile": 8, "multiplier": 1.5},
        }
        config = speed_config.get(speed, speed_config["average"])

        try:
            fee_history = await self._get_fee_history()
            base_fees = fee_history["base_fee_per_gas"]
            rewards = fee_history["reward"]

            current_base_fee = base_fees[-1] if base_fees else 0

            percentile_index = min(config["priority_percentile"], len(fee_history["percentiles"]) - 1)
            recent_rewards = []
            for block_rewards in rewards[-20:]:
                if block_rewards and len(block_rewards) > percentile_index:
                    recent_rewards.append(block_rewards[percentile_index])

            if recent_rewards:
                avg_priority_fee = int(statistics.mean(recent_rewards))
            else:
                avg_priority_fee = await self._chain.get_max_priority_fee_per_gas()

            max_priority_fee = min(
                int(avg_priority_fee * config["multiplier"]),
                self._max_priority_fee,
            )

            max_fee = int(
                (current_base_fee * 2 * config["multiplier"]) + max_priority_fee
            )

            return {
                "max_fee_per_gas": max_fee,
                "max_priority_fee_per_gas": max_priority_fee,
                "base_fee_per_gas": current_base_fee,
            }
        except Exception as e:
            logger.warning(f"Failed to estimate EIP-1559 fees, falling back: {e}")
            gas_price = await self.estimate_gas_price(speed)
            return {
                "max_fee_per_gas": gas_price,
                "max_priority_fee_per_gas": gas_price // 2,
                "base_fee_per_gas": gas_price // 2,
            }

    async def estimate_gas_limit(
        self,
        to: Optional[Address] = None,
        from_address: Optional[Address] = None,
        value: Optional[WeiAmount] = None,
        data: Optional[HexString] = None,
    ) -> GasAmount:
        try:
            estimated = await self._chain.estimate_gas(
                to=to, from_address=from_address, value=value, data=data
            )
            buffer = int(estimated * 0.1)
            return estimated + buffer
        except Exception as e:
            raise GasEstimationError(f"Failed to estimate gas limit: {e}")

    async def estimate_transaction_cost(
        self,
        to: Optional[Address] = None,
        from_address: Optional[Address] = None,
        value: Optional[WeiAmount] = None,
        data: Optional[HexString] = None,
        speed: str = "average",
    ) -> GasEstimate:
        try:
            gas_limit_task = self.estimate_gas_limit(to, from_address, value, data)
            eip1559_task = self.estimate_eip1559_fees(speed)
            gas_price_task = self.estimate_gas_price(speed)

            gas_limit, eip1559_fees, gas_price = await asyncio.gather(
                gas_limit_task, eip1559_task, gas_price_task
            )

            estimated_cost = gas_limit * eip1559_fees["max_fee_per_gas"]
            confidence = self._calculate_confidence()

            return GasEstimate(
                gas_limit=gas_limit,
                gas_price=gas_price,
                max_fee_per_gas=eip1559_fees["max_fee_per_gas"],
                max_priority_fee_per_gas=eip1559_fees["max_priority_fee_per_gas"],
                estimated_cost=estimated_cost,
                confidence=confidence,
                recommendation=speed,
            )
        except Exception as e:
            raise GasEstimationError(f"Failed to estimate transaction cost: {e}")

    def _calculate_confidence(self) -> float:
        try:
            fee_history = self._price_cache
            if not fee_history:
                return 0.7

            gas_ratios = fee_history.get("gas_used_ratio", [])
            if len(gas_ratios) < 5:
                return 0.75

            recent_avg = sum(gas_ratios[-5:]) / 5
            volatility = statistics.pstdev(gas_ratios[-20:]) if len(gas_ratios) >= 20 else 0.1

            confidence = 0.5 + (0.5 - volatility)
            if recent_avg > 0.9:
                confidence -= 0.2
            elif recent_avg < 0.3:
                confidence += 0.1

            return max(0.1, min(0.99, confidence))
        except Exception:
            return 0.7

    async def get_gas_price_history(self, blocks: int = 100) -> Dict[str, Any]:
        try:
            fee_history = await self._get_fee_history()
            base_fees = fee_history["base_fee_per_gas"][-blocks:]
            gas_ratios = fee_history["gas_used_ratio"][-blocks:]

            avg_base_fee = sum(base_fees) / len(base_fees) if base_fees else 0
            min_base_fee = min(base_fees) if base_fees else 0
            max_base_fee = max(base_fees) if base_fees else 0

            all_rewards = []
            for block_rewards in fee_history["reward"][-blocks:]:
                all_rewards.extend(block_rewards)
            avg_reward = sum(all_rewards) / len(all_rewards) if all_rewards else 0

            return {
                "blocks_analyzed": len(base_fees),
                "base_fee": {
                    "average": int(avg_base_fee),
                    "min": int(min_base_fee),
                    "max": int(max_base_fee),
                    "current": int(base_fees[-1]) if base_fees else 0,
                    "history": [int(x) for x in base_fees],
                },
                "priority_fee": {
                    "average": int(avg_reward),
                    "history": [[int(x) for x in block] for block in fee_history["reward"][-blocks:]],
                },
                "gas_used_ratio": {
                    "average": sum(gas_ratios) / len(gas_ratios) if gas_ratios else 0,
                    "history": gas_ratios,
                },
            }
        except Exception as e:
            raise GasEstimationError(f"Failed to get gas price history: {e}")

    async def predict_gas_price(self, time_horizon_minutes: int = 10) -> Dict[str, Any]:
        try:
            history = await self.get_gas_price_history(blocks=50)
            base_fee_history = history["base_fee"]["history"]
            gas_ratio_history = history["gas_used_ratio"]["history"]

            if len(base_fee_history) < 2:
                return {
                    "predicted_gas_price": history["base_fee"]["current"],
                    "trend": "stable",
                    "confidence": 0.5,
                    "time_horizon_minutes": time_horizon_minutes,
                }

            recent_trend = base_fee_history[-1] - base_fee_history[-2]
            avg_gas_ratio = sum(gas_ratio_history[-5:]) / 5 if gas_ratio_history else 0.5

            trend = "stable"
            if recent_trend > 0 and avg_gas_ratio > 0.8:
                trend = "increasing"
            elif recent_trend < 0 and avg_gas_ratio < 0.3:
                trend = "decreasing"

            blocks_ahead = time_horizon_minutes // 0.2
            predicted_price = base_fee_history[-1]
            if trend == "increasing":
                predicted_price = int(predicted_price * (1 + 0.001 * blocks_ahead))
            elif trend == "decreasing":
                predicted_price = int(predicted_price * (1 - 0.001 * blocks_ahead))

            confidence = 0.6
            if trend == "stable":
                confidence = 0.8
            elif avg_gas_ratio > 0.9 or avg_gas_ratio < 0.1:
                confidence = 0.4

            return {
                "predicted_gas_price": predicted_price,
                "predicted_max_fee": int(predicted_price * 1.5),
                "predicted_priority_fee": int(predicted_price * 0.1),
                "trend": trend,
                "confidence": confidence,
                "time_horizon_minutes": time_horizon_minutes,
                "blocks_ahead": int(blocks_ahead),
            }
        except Exception as e:
            raise GasEstimationError(f"Failed to predict gas price: {e}")

    async def get_recommendation(self) -> Dict[str, Any]:
        try:
            prediction = await self.predict_gas_price(10)
            history = await self.get_gas_price_history(20)

            current_base_fee = history["base_fee"]["current"]
            predicted = prediction["predicted_gas_price"]

            recommendation = "now"
            if predicted < current_base_fee * 0.9 and prediction["confidence"] > 0.7:
                recommendation = "wait"
            elif predicted > current_base_fee * 1.1 and prediction["confidence"] > 0.7:
                recommendation = "urgent"

            speed_options = []
            for speed in ["slow", "average", "fast", "rapid"]:
                fees = await self.estimate_eip1559_fees(speed)
                speed_options.append(
                    {
                        "speed": speed,
                        "max_fee_per_gas": fees["max_fee_per_gas"],
                        "max_priority_fee_per_gas": fees["max_priority_fee_per_gas"],
                        "estimated_confirmation_time_minutes": {
                            "slow": 5,
                            "average": 2,
                            "fast": 1,
                            "rapid": 0.5,
                        }[speed],
                    }
                )

            return {
                "recommendation": recommendation,
                "current_gas_price": current_base_fee,
                "predicted_gas_price": predicted,
                "trend": prediction["trend"],
                "confidence": prediction["confidence"],
                "speed_options": speed_options,
                "network_congestion": history["gas_used_ratio"]["average"],
            }
        except Exception as e:
            raise GasEstimationError(f"Failed to get recommendation: {e}")
