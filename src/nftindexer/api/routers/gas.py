from typing import Any, Dict, List, Optional
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from ...core.schemas import ResourceResponse
from ...utils import get_logger
from ..deps import GasEstimatorModuleDep, TraceIdDep, ApiKeyDep

logger = get_logger(__name__)
router = APIRouter(prefix="/api/v1/gas", tags=["Gas Estimator"])


class EstimateGasRequest(BaseModel):
    chain_id: int
    to: Optional[str] = None
    data: str = "0x"
    value: str = "0"
    from_address: Optional[str] = None


@router.get("/{chain_id}/estimate", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def estimate_gas(
    chain_id: int,
    gas_estimator: GasEstimatorModuleDep,
    trace_id: TraceIdDep,
):
    try:
        estimate = await gas_estimator.estimate_gas(chain_id)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=estimate,
        )
    except Exception as e:
        logger.error(f"Error estimating gas for chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain_id}/estimate-for-tx", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def estimate_gas_for_transaction(
    chain_id: int,
    request: EstimateGasRequest,
    gas_estimator: GasEstimatorModuleDep,
    trace_id: TraceIdDep,
):
    try:
        if request.chain_id != chain_id:
            raise HTTPException(status_code=400, detail="Chain ID mismatch")
        estimate = await gas_estimator.estimate_gas_for_transaction(
            chain_id=chain_id,
            to=request.to,
            data=request.data,
            value=request.value,
            from_address=request.from_address,
        )
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=estimate,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error estimating gas for transaction on chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain_id}/current", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_current_gas_prices(
    chain_id: int,
    gas_estimator: GasEstimatorModuleDep,
    trace_id: TraceIdDep,
):
    try:
        prices = await gas_estimator.get_current_gas_prices(chain_id)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=prices,
        )
    except Exception as e:
        logger.error(f"Error getting current gas prices for chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain_id}/history", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_gas_history(
    chain_id: int,
    hours: int = Query(24, ge=1, le=168),
    gas_estimator: GasEstimatorModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        history = await gas_estimator.get_gas_history(chain_id, hours=hours)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={"history": history, "hours": hours},
        )
    except Exception as e:
        logger.error(f"Error getting gas history for chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain_id}/stats", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_chain_gas_stats(
    chain_id: int,
    gas_estimator: GasEstimatorModuleDep,
    trace_id: TraceIdDep,
):
    try:
        stats = await gas_estimator.get_chain_gas_stats(chain_id)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=stats,
        )
    except Exception as e:
        logger.error(f"Error getting gas stats for chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain_id}/oracle", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_gas_price_oracle(
    chain_id: int,
    gas_estimator: GasEstimatorModuleDep,
    trace_id: TraceIdDep,
):
    try:
        oracle = await gas_estimator.get_gas_price_oracle(chain_id)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=oracle,
        )
    except Exception as e:
        logger.error(f"Error getting gas price oracle for chain {chain_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))
