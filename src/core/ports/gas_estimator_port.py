from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Dict, Optional

from src.shared.types import Address, GasAmount, GasEstimate, HexString, WeiAmount


class GasFeeHistory(ABC):
    @abstractmethod
    async def get_fee_history(
        self,
        block_count: int,
        last_block: Optional[int | str] = None,
        reward_percentiles: Optional[list[int]] = None,
    ) -> Dict[str, Any]: ...


class IGasEstimatorPort(ABC):
    @abstractmethod
    async def estimate_gas_price(
        self,
        speed: str = "average",
    ) -> WeiAmount: ...

    @abstractmethod
    async def estimate_eip1559_fees(
        self,
        speed: str = "average",
    ) -> Dict[str, WeiAmount]: ...

    @abstractmethod
    async def estimate_gas_limit(
        self,
        to: Optional[Address] = None,
        from_address: Optional[Address] = None,
        value: Optional[WeiAmount] = None,
        data: Optional[HexString] = None,
    ) -> GasAmount: ...

    @abstractmethod
    async def estimate_transaction_cost(
        self,
        to: Optional[Address] = None,
        from_address: Optional[Address] = None,
        value: Optional[WeiAmount] = None,
        data: Optional[HexString] = None,
        speed: str = "average",
    ) -> GasEstimate: ...

    @abstractmethod
    async def get_gas_price_history(
        self,
        blocks: int = 100,
    ) -> Dict[str, Any]: ...

    @abstractmethod
    async def predict_gas_price(
        self,
        time_horizon_minutes: int = 10,
    ) -> Dict[str, Any]: ...

    @abstractmethod
    async def get_recommendation(self) -> Dict[str, Any]: ...
