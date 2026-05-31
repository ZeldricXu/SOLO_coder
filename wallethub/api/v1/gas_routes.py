from fastapi import APIRouter, HTTPException
from typing import Optional

from wallethub.api.models.transaction_models import (
    GasEstimateRequest,
    GasEstimateResponse,
    GasFeeEstimate,
)
from wallethub.core import GasEstimationError

router = APIRouter(prefix="/gas", tags=["Gas"])


@router.get("/{chain}/estimate", response_model=GasEstimateResponse)
async def estimate_gas(chain: str):
    try:
        from wallethub.modules.chain_adapter import ChainAdapter
        from wallethub.modules.gas_estimator import GasEstimator

        adapter = ChainAdapter()
        client = adapter.get_client(chain)

        estimator = GasEstimator(client)
        estimate = await estimator.estimate_all()

        def to_fee_estimate(data: dict) -> GasFeeEstimate:
            return GasFeeEstimate(
                gas_price=data.get("gas_price"),
                max_fee_per_gas=data.get("max_fee_per_gas"),
                max_priority_fee_per_gas=data.get("max_priority_fee_per_gas"),
                estimated_cost=(data.get("max_fee_per_gas") or data.get("gas_price") or 0) * 21000,
            )

        return GasEstimateResponse(
            chain=chain,
            block_number=estimate.block_number,
            base_fee=estimate.base_fee,
            gas_limit=21000,
            slow=to_fee_estimate(estimate.slow),
            standard=to_fee_estimate(estimate.standard),
            fast=to_fee_estimate(estimate.fast),
            urgent=to_fee_estimate(estimate.urgent),
        )
    except GasEstimationError as e:
        raise HTTPException(status_code=500, detail=e.message)


@router.post("/{chain}/estimate")
async def estimate_gas_for_transaction(chain: str, request: GasEstimateRequest):
    try:
        from wallethub.modules.chain_adapter import ChainAdapter
        from wallethub.modules.gas_estimator import GasEstimator

        adapter = ChainAdapter()
        client = adapter.get_client(chain)

        tx_params = {
            "to": request.to_address,
            "value": request.value,
            "data": request.data or "0x",
        }
        if request.from_address:
            tx_params["from"] = request.from_address

        estimator = GasEstimator(client)
        result = await estimator.estimate_for_transaction(tx_params)

        return {
            "chain": chain,
            "block_number": result["block_number"],
            "base_fee": result["base_fee"],
            "gas_limit": result["gas_limit"],
            "max_fee_per_gas": result.get("max_fee_per_gas"),
            "max_priority_fee_per_gas": result.get("max_priority_fee_per_gas"),
            "gas_price": result.get("gas_price"),
            "estimated_cost": result["estimated_cost"],
        }
    except GasEstimationError as e:
        raise HTTPException(status_code=500, detail=e.message)


@router.get("/{chain}/history")
async def get_gas_price_history(chain: str, limit: int = 10):
    try:
        from wallethub.modules.chain_adapter import ChainAdapter
        from wallethub.modules.gas_estimator import GasEstimator

        adapter = ChainAdapter()
        client = adapter.get_client(chain)

        estimator = GasEstimator(client)
        estimates = estimator.get_recent_estimates(limit)

        return {
            "chain": chain,
            "history": [
                {
                    "timestamp": e.timestamp.isoformat(),
                    "block_number": e.block_number,
                    "base_fee": e.base_fee,
                    "standard_max_fee": e.standard.get("max_fee_per_gas"),
                    "standard_priority_fee": e.standard.get("max_priority_fee_per_gas"),
                }
                for e in estimates
            ],
        }
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
