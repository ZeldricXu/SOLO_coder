import asyncio
import time
from dataclasses import dataclass
from typing import Any, Dict, List, Optional
from datetime import datetime, timezone

from ..config import get_settings
from ..db import async_session, GasPriceHistory
from ..utils import (
    get_logger,
    generate_id,
    GasEstimationError,
    ValidationError,
)

logger = get_logger(__name__)


@dataclass
class GasEstimate:
    chain_id: int
    gas_price: int
    max_fee_per_gas: int
    max_priority_fee_per_gas: int
    confidence_level: str
    estimated_time_seconds: int
    historical_samples: int


@dataclass
class GasPricePoint:
    block_number: int
    timestamp: datetime
    base_fee: int
    priority_fee_low: int
    priority_fee_medium: int
    priority_fee_high: int


class GasEstimatorModule:
    CONFIDENCE_LEVELS = {
        "low": {"percentile": 25, "multiplier": 1.0},
        "medium": {"percentile": 50, "multiplier": 1.2},
        "high": {"percentile": 75, "multiplier": 1.5},
    }

    def __init__(self):
        self.settings = get_settings()
        self._initialized = False
        self._price_cache: Dict[int, Dict[str, Any]] = {}
        self._last_update: Dict[int, float] = {}
        self._update_lock = asyncio.Lock()

    async def initialize(self) -> None:
        if self._initialized:
            return
        logger.info("Initializing gas estimator module")
        self._initialized = True
        logger.info("Gas estimator module initialized")

    async def shutdown(self) -> None:
        if not self._initialized:
            return
        logger.info("Shutting down gas estimator module")
        self._price_cache.clear()
        self._last_update.clear()
        self._initialized = False
        logger.info("Gas estimator module shutdown complete")

    async def estimate_gas(self, chain_id: int, confidence: str = "medium") -> GasEstimate:
        if confidence not in self.CONFIDENCE_LEVELS:
            raise ValidationError(
                f"Invalid confidence level: {confidence}",
                details={"supported": list(self.CONFIDENCE_LEVELS.keys())},
            )

        cache = await self._get_cached_prices(chain_id)
        if cache:
            return self._build_estimate_from_cache(chain_id, cache, confidence)

        prices = await self._fetch_and_store_gas_prices(chain_id)
        return self._build_estimate(chain_id, prices, confidence)

    async def _get_cached_prices(self, chain_id: int) -> Optional[Dict[str, Any]]:
        now = time.time()
        last_update = self._last_update.get(chain_id, 0)
        cache_ttl = self.settings.gas.cache_ttl

        if now - last_update < cache_ttl:
            return self._price_cache.get(chain_id)
        return None

    async def _fetch_and_store_gas_prices(self, chain_id: int) -> Dict[str, Any]:
        from .chain_adapter import get_chain_adapter

        async with self._update_lock:
            now = time.time()
            last_update = self._last_update.get(chain_id, 0)
            cache_ttl = self.settings.gas.cache_ttl

            if now - last_update < cache_ttl:
                return self._price_cache.get(chain_id, {})

            chain_adapter = get_chain_adapter()
            gas_settings = self.settings.gas

            try:
                current_block = await chain_adapter.get_block_number(chain_id)
                fee_history = await chain_adapter.get_fee_history(
                    chain_id,
                    block_count=gas_settings.history_window_blocks,
                    reward_percentiles=[25, 50, 75],
                )

                base_fees = [int(f, 16) for f in fee_history.get("baseFeePerGas", []) if f]
                rewards = fee_history.get("reward", [])

                if not base_fees:
                    raise GasEstimationError("No base fee data available")

                current_base_fee = base_fees[-1] if base_fees else gas_settings.fallback_gas_price

                priority_fees = []
                for block_rewards in rewards:
                    if block_rewards:
                        priority_fees.append([int(r, 16) for r in block_rewards])

                if priority_fees:
                    priority_low = self._percentile([p[0] for p in priority_fees if len(p) > 0], 25)
                    priority_medium = self._percentile([p[1] for p in priority_fees if len(p) > 1], 50)
                    priority_high = self._percentile([p[2] for p in priority_fees if len(p) > 2], 75)
                else:
                    priority_low = gas_settings.fallback_gas_price // 2
                    priority_medium = gas_settings.fallback_gas_price
                    priority_high = int(gas_settings.fallback_gas_price * 1.5)

                prices = {
                    "base_fee": current_base_fee,
                    "priority_low": priority_low,
                    "priority_medium": priority_medium,
                    "priority_high": priority_high,
                    "block_number": current_block,
                    "timestamp": now,
                    "samples": len(base_fees),
                }

                await self._store_gas_prices(chain_id, current_block, prices)

                self._price_cache[chain_id] = prices
                self._last_update[chain_id] = now

                return prices

            except Exception as e:
                logger.error(f"Failed to fetch gas prices for chain {chain_id}: {e}")
                fallback = {
                    "base_fee": gas_settings.fallback_gas_price,
                    "priority_low": gas_settings.fallback_gas_price // 2,
                    "priority_medium": gas_settings.fallback_gas_price,
                    "priority_high": int(gas_settings.fallback_gas_price * 1.5),
                    "block_number": 0,
                    "timestamp": now,
                    "samples": 0,
                }
                self._price_cache[chain_id] = fallback
                self._last_update[chain_id] = now
                return fallback

    async def _store_gas_prices(self, chain_id: int, block_number: int, prices: Dict[str, Any]) -> None:
        async with async_session() as session:
            history = GasPriceHistory(
                chain_id=chain_id,
                block_number=block_number,
                timestamp=datetime.now(timezone.utc),
                base_fee_per_gas=str(prices["base_fee"]),
                priority_fee_low=str(prices["priority_low"]),
                priority_fee_medium=str(prices["priority_medium"]),
                priority_fee_high=str(prices["priority_high"]),
                gas_used_ratio=0.0,
                pending_transactions=0,
            )
            session.add(history)
            try:
                await session.commit()
            except Exception as e:
                logger.debug(f"Failed to store gas history (may be duplicate): {e}")

    def _build_estimate_from_cache(
        self, chain_id: int, cache: Dict[str, Any], confidence: str
    ) -> GasEstimate:
        return self._build_estimate(chain_id, cache, confidence)

    def _build_estimate(
        self, chain_id: int, prices: Dict[str, Any], confidence: str
    ) -> GasEstimate:
        gas_settings = self.settings.gas
        level = self.CONFIDENCE_LEVELS[confidence]

        base_fee = prices.get("base_fee", gas_settings.fallback_gas_price)
        priority_key = f"priority_{confidence}"
        priority_fee = prices.get(priority_key, gas_settings.fallback_gas_price)

        max_priority_fee = int(priority_fee * gas_settings.max_priority_fee_multiplier)
        max_fee = int((base_fee + max_priority_fee) * gas_settings.max_fee_multiplier)

        max_fee = max(min(max_fee, gas_settings.max_gas_price), gas_settings.min_gas_price)
        max_priority_fee = max(min(max_priority_fee, gas_settings.max_gas_price), gas_settings.min_gas_price)
        gas_price = max(min(base_fee + priority_fee, gas_settings.max_gas_price), gas_settings.min_gas_price)

        estimated_time = self._estimate_confirmation_time(confidence, prices)

        return GasEstimate(
            chain_id=chain_id,
            gas_price=gas_price,
            max_fee_per_gas=max_fee,
            max_priority_fee_per_gas=max_priority_fee,
            confidence_level=confidence,
            estimated_time_seconds=estimated_time,
            historical_samples=prices.get("samples", 0),
        )

    def _estimate_confirmation_time(self, confidence: str, prices: Dict[str, Any]) -> int:
        base_times = {
            "low": 120,
            "medium": 60,
            "high": 15,
        }
        return base_times.get(confidence, 60)

    def _percentile(self, data: List[int], percentile: int) -> int:
        if not data:
            return 0
        sorted_data = sorted(data)
        k = (len(sorted_data) - 1) * (percentile / 100)
        f = int(k)
        c = f + 1
        if f >= len(sorted_data) - 1:
            return sorted_data[-1]
        return int(sorted_data[f] + (sorted_data[c] - sorted_data[f]) * (k - f))

    async def get_gas_history(
        self,
        chain_id: int,
        start_block: Optional[int] = None,
        end_block: Optional[int] = None,
        limit: int = 100,
    ) -> List[GasPricePoint]:
        from sqlalchemy import select, and_

        async with async_session() as session:
            query = select(GasPriceHistory).where(GasPriceHistory.chain_id == chain_id)

            if start_block:
                query = query.where(GasPriceHistory.block_number >= start_block)
            if end_block:
                query = query.where(GasPriceHistory.block_number <= end_block)

            query = query.order_by(GasPriceHistory.block_number.desc()).limit(limit)
            result = await session.execute(query)
            history = result.scalars().all()

            return [
                GasPricePoint(
                    block_number=h.block_number,
                    timestamp=h.timestamp,
                    base_fee=int(h.base_fee_per_gas or "0"),
                    priority_fee_low=int(h.priority_fee_low),
                    priority_fee_medium=int(h.priority_fee_medium),
                    priority_fee_high=int(h.priority_fee_high),
                )
                for h in history
            ]

    async def get_current_gas_prices(self, chain_id: int) -> Dict[str, Any]:
        prices = await self._fetch_and_store_gas_prices(chain_id)
        return {
            "chain_id": chain_id,
            "base_fee": prices["base_fee"],
            "base_fee_gwei": prices["base_fee"] / 1e9,
            "priority_fee": {
                "low": prices["priority_low"],
                "low_gwei": prices["priority_low"] / 1e9,
                "medium": prices["priority_medium"],
                "medium_gwei": prices["priority_medium"] / 1e9,
                "high": prices["priority_high"],
                "high_gwei": prices["priority_high"] / 1e9,
            },
            "block_number": prices["block_number"],
            "timestamp": prices["timestamp"],
        }

    async def estimate_gas_for_transaction(
        self,
        chain_id: int,
        to: str,
        data: str = "0x",
        value: int = 0,
        from_address: Optional[str] = None,
        confidence: str = "medium",
    ) -> Dict[str, Any]:
        from .chain_adapter import get_chain_adapter

        chain_adapter = get_chain_adapter()

        try:
            tx_params = {
                "to": to,
                "data": data,
                "value": value,
            }
            if from_address:
                tx_params["from"] = from_address

            gas_limit = 21000

            estimate = await self.estimate_gas(chain_id, confidence)

            total_cost_low = gas_limit * estimate.gas_price
            total_cost_high = gas_limit * estimate.max_fee_per_gas

            return {
                "chain_id": chain_id,
                "to": to,
                "gas_limit": gas_limit,
                "gas_price": {
                    "wei": estimate.gas_price,
                    "gwei": estimate.gas_price / 1e9,
                },
                "max_fee_per_gas": {
                    "wei": estimate.max_fee_per_gas,
                    "gwei": estimate.max_fee_per_gas / 1e9,
                },
                "max_priority_fee_per_gas": {
                    "wei": estimate.max_priority_fee_per_gas,
                    "gwei": estimate.max_priority_fee_per_gas / 1e9,
                },
                "estimated_cost": {
                    "low_wei": total_cost_low,
                    "low_eth": total_cost_low / 1e18,
                    "high_wei": total_cost_high,
                    "high_eth": total_cost_high / 1e18,
                },
                "confidence": confidence,
                "estimated_time_seconds": estimate.estimated_time_seconds,
            }

        except Exception as e:
            logger.error(f"Failed to estimate gas for transaction: {e}")
            raise GasEstimationError(f"Failed to estimate gas: {e}")

    async def get_chain_gas_stats(self, chain_id: int, hours: int = 24) -> Dict[str, Any]:
        from sqlalchemy import select, func
        from datetime import timedelta

        cutoff_time = datetime.now(timezone.utc) - timedelta(hours=hours)

        async with async_session() as session:
            query = select(GasPriceHistory).where(
                GasPriceHistory.chain_id == chain_id,
                GasPriceHistory.timestamp >= cutoff_time,
            )
            result = await session.execute(query)
            history = result.scalars().all()

            if not history:
                return {"chain_id": chain_id, "samples": 0}

            base_fees = [int(h.base_fee_per_gas or "0") for h in history]
            priority_medium = [int(h.priority_fee_medium) for h in history]

            return {
                "chain_id": chain_id,
                "samples": len(history),
                "time_window_hours": hours,
                "base_fee": {
                    "min": min(base_fees),
                    "max": max(base_fees),
                    "avg": int(sum(base_fees) / len(base_fees)),
                    "current": base_fees[-1] if base_fees else 0,
                },
                "priority_fee_medium": {
                    "min": min(priority_medium),
                    "max": max(priority_medium),
                    "avg": int(sum(priority_medium) / len(priority_medium)),
                    "current": priority_medium[-1] if priority_medium else 0,
                },
            }

    async def batch_estimate_gas(self, chain_ids: List[int], confidence: str = "medium") -> Dict[int, GasEstimate]:
        results = {}
        for chain_id in chain_ids:
            try:
                estimate = await self.estimate_gas(chain_id, confidence)
                results[chain_id] = estimate
            except Exception as e:
                logger.error(f"Failed to estimate gas for chain {chain_id}: {e}")
        return results

    async def get_gas_price_oracle(self, chain_id: int) -> Dict[str, Any]:
        prices = await self._fetch_and_store_gas_prices(chain_id)
        base_fee = prices["base_fee"]

        return {
            "chain_id": chain_id,
            "oracle_price": base_fee,
            "oracle_price_gwei": base_fee / 1e9,
            "timestamp": prices["timestamp"],
            "block_number": prices["block_number"],
            "source": "on-chain",
            "confidence": prices["samples"] > 10,
        }


_gas_estimator_module: Optional[GasEstimatorModule] = None


def get_gas_estimator_module() -> GasEstimatorModule:
    global _gas_estimator_module
    if _gas_estimator_module is None:
        _gas_estimator_module = GasEstimatorModule()
    return _gas_estimator_module
