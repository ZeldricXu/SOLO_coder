from __future__ import annotations

from typing import Any, Dict, Optional

from fastapi import APIRouter, Depends, HTTPException, Query

from src.shared.container import Container, container
from src.shared.types import (
    Address,
    APIResponse,
    Chain,
    GasEstimate,
    HexString,
    WeiAmount,
)

router = APIRouter(prefix="/gas", tags=["gas"])


async def get_container() -> Container:
    return container


@router.get("/{chain}/estimate", response_model=APIResponse[GasEstimate])
async def estimate_transaction_cost(
    chain: Chain,
    to: Optional[Address] = None,
    from_address: Optional[Address] = None,
    value: WeiAmount = 0,
    data: HexString = "0x",
    speed: str = Query("average", pattern="^(slow|average|fast|rapid)$"),
    container: Container = Depends(get_container),
):
    try:
        estimator = container.get_gas_estimator(chain)
        estimate = await estimator.estimate_transaction_cost(
            to=to,
            from_address=from_address,
            value=value,
            data=data,
            speed=speed,
        )
        return APIResponse.success(data=estimate)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/price", response_model=APIResponse[WeiAmount])
async def get_gas_price(
    chain: Chain,
    speed: str = Query("average", pattern="^(slow|average|fast|rapid)$"),
    container: Container = Depends(get_container),
):
    try:
        estimator = container.get_gas_estimator(chain)
        price = await estimator.estimate_gas_price(speed)
        return APIResponse.success(data=price)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/eip1559", response_model=APIResponse[Dict[str, WeiAmount]])
async def get_eip1559_fees(
    chain: Chain,
    speed: str = Query("average", pattern="^(slow|average|fast|rapid)$"),
    container: Container = Depends(get_container),
):
    try:
        estimator = container.get_gas_estimator(chain)
        fees = await estimator.estimate_eip1559_fees(speed)
        return APIResponse.success(data=fees)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/history", response_model=APIResponse[Dict[str, Any]])
async def get_gas_price_history(
    chain: Chain,
    blocks: int = Query(100, ge=1, le=1000),
    container: Container = Depends(get_container),
):
    try:
        estimator = container.get_gas_estimator(chain)
        history = await estimator.get_gas_price_history(blocks)
        return APIResponse.success(data=history)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/predict", response_model=APIResponse[Dict[str, Any]])
async def predict_gas_price(
    chain: Chain,
    time_horizon_minutes: int = Query(10, ge=1, le=60),
    container: Container = Depends(get_container),
):
    try:
        estimator = container.get_gas_estimator(chain)
        prediction = await estimator.predict_gas_price(time_horizon_minutes)
        return APIResponse.success(data=prediction)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/recommendation", response_model=APIResponse[Dict[str, Any]])
async def get_gas_recommendation(
    chain: Chain,
    container: Container = Depends(get_container),
):
    try:
        estimator = container.get_gas_estimator(chain)
        recommendation = await estimator.get_recommendation()
        return APIResponse.success(data=recommendation)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
