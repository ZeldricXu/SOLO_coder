from __future__ import annotations

from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query

from src.shared.container import Container, container
from src.shared.types import (
    Address,
    APIResponse,
    Chain,
    HexString,
    SignedTransaction,
    WeiAmount,
)

router = APIRouter(prefix="/transactions", tags=["transactions"])


async def get_container() -> Container:
    return container


@router.post("/{chain}/build", response_model=APIResponse[Dict[str, Any]])
async def build_transaction(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        builder = container.get_transaction_builder(chain)
        tx = await builder.build_transaction(
            to=request.get("to"),
            from_address=request.get("from_address"),
            value=request.get("value", 0),
            data=request.get("data", "0x"),
            gas_limit=request.get("gas_limit"),
            gas_price=request.get("gas_price"),
            max_fee_per_gas=request.get("max_fee_per_gas"),
            max_priority_fee_per_gas=request.get("max_priority_fee_per_gas"),
            nonce=request.get("nonce"),
        )
        return APIResponse.success(data=tx)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/deploy", response_model=APIResponse[Dict[str, Any]])
async def build_contract_deployment(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        builder = container.get_transaction_builder(chain)
        tx = await builder.build_contract_deployment(
            bytecode=request["bytecode"],
            constructor_args=request.get("constructor_args"),
            abi=request.get("abi"),
            from_address=request.get("from_address"),
            value=request.get("value", 0),
            gas_limit=request.get("gas_limit"),
            gas_price=request.get("gas_price"),
            nonce=request.get("nonce"),
        )
        return APIResponse.success(data=tx)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/call", response_model=APIResponse[Dict[str, Any]])
async def build_contract_call(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        builder = container.get_transaction_builder(chain)
        tx = await builder.build_contract_call(
            contract_address=request["contract_address"],
            function_name=request["function_name"],
            function_args=request.get("function_args"),
            abi=request["abi"],
            from_address=request.get("from_address"),
            value=request.get("value", 0),
            gas_limit=request.get("gas_limit"),
            gas_price=request.get("gas_price"),
            nonce=request.get("nonce"),
        )
        return APIResponse.success(data=tx)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/sign", response_model=APIResponse[SignedTransaction])
async def sign_transaction(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        builder = container.get_transaction_builder(chain)
        signed = await builder.sign_transaction(
            transaction=request["transaction"],
            private_key=request["private_key"],
        )
        return APIResponse.success(data=signed)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/sign/message", response_model=APIResponse[HexString])
async def sign_message(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        builder = container.get_transaction_builder(chain)
        signature = await builder.sign_message(
            message=request["message"],
            private_key=request["private_key"],
            sign_type=request.get("sign_type", "ecdsa"),
        )
        return APIResponse.success(data=signature)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/verify", response_model=APIResponse[bool])
async def verify_transaction(
    chain: Chain,
    signed_tx: SignedTransaction,
    container: Container = Depends(get_container),
):
    try:
        builder = container.get_transaction_builder(chain)
        valid = await builder.verify_transaction(signed_tx)
        return APIResponse.success(data=valid)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/encode", response_model=APIResponse[HexString])
async def encode_function_call(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        builder = container.get_transaction_builder(chain)
        data = await builder.encode_function_call(
            function_name=request["function_name"],
            function_args=request.get("function_args"),
            abi=request["abi"],
        )
        return APIResponse.success(data=data)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/decode", response_model=APIResponse[Dict[str, Any]])
async def decode_function_input(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        builder = container.get_transaction_builder(chain)
        result = await builder.decode_function_input(
            data=request["data"],
            abi=request["abi"],
        )
        return APIResponse.success(data=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/optimize", response_model=APIResponse[Dict[str, Any]])
async def optimize_transaction(
    chain: Chain,
    request: Dict[str, Any],
    speed: str = Query("average", pattern="^(slow|average|fast|rapid)$"),
    container: Container = Depends(get_container),
):
    try:
        optimizer = container.gas_optimizer
        optimized = await optimizer.optimize_gas_price(
            transaction=request["transaction"],
            speed=speed,
        )
        return APIResponse.success(data=optimized)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/optimize/limit", response_model=APIResponse[Dict[str, Any]])
async def optimize_gas_limit(
    chain: Chain,
    request: Dict[str, Any],
    buffer_percent: float = Query(10.0, ge=0, le=100),
    container: Container = Depends(get_container),
):
    try:
        optimizer = container.gas_optimizer
        optimized = await optimizer.optimize_gas_limit(
            transaction=request["transaction"],
            buffer_percent=buffer_percent,
        )
        return APIResponse.success(data=optimized)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/savings", response_model=APIResponse[List[Dict[str, Any]]])
async def get_gas_savings_suggestions(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        optimizer = container.gas_optimizer
        suggestions = await optimizer.suggest_gas_savings(
            transaction=request["transaction"],
        )
        return APIResponse.success(data=suggestions)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/batch", response_model=APIResponse[Dict[str, Any]])
async def batch_transactions(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        optimizer = container.gas_optimizer
        batched = await optimizer.batch_transactions(
            transactions=request["transactions"],
            from_address=request["from_address"],
        )
        return APIResponse.success(data=batched)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/multisig/create", response_model=APIResponse[Address])
async def create_multisig_wallet(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        multisig = container.multi_sig_service
        address = await multisig.create_multisig_wallet(
            owners=request["owners"],
            threshold=request["threshold"],
            chain_id=request.get("chain_id", chain.value),
        )
        return APIResponse.success(data=address)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/multisig/propose", response_model=APIResponse[str])
async def propose_multisig_transaction(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        multisig = container.multi_sig_service
        tx_id = await multisig.propose_transaction(
            multisig_address=request["multisig_address"],
            to=request["to"],
            value=request.get("value", 0),
            data=request.get("data", "0x"),
            proposer=request["proposer"],
        )
        return APIResponse.success(data=tx_id)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/multisig/approve", response_model=APIResponse[bool])
async def approve_multisig_transaction(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        multisig = container.multi_sig_service
        success = await multisig.approve_transaction(
            multisig_address=request["multisig_address"],
            tx_id=request["tx_id"],
            approver=request["approver"],
        )
        return APIResponse.success(data=success)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/{chain}/multisig/execute", response_model=APIResponse[Optional[str]])
async def execute_multisig_transaction(
    chain: Chain,
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        multisig = container.multi_sig_service
        tx_hash = await multisig.execute_transaction(
            multisig_address=request["multisig_address"],
            tx_id=request["tx_id"],
        )
        return APIResponse.success(data=tx_hash)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/multisig/{multisig_address}/transactions", response_model=APIResponse[List[Dict[str, Any]]])
async def list_multisig_transactions(
    chain: Chain,
    multisig_address: Address,
    status: Optional[str] = None,
    container: Container = Depends(get_container),
):
    try:
        multisig = container.multi_sig_service
        txs = await multisig.list_transactions(
            multisig_address=multisig_address,
            status=status,
        )
        return APIResponse.success(data=txs)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/multisig/{multisig_address}/owners", response_model=APIResponse[List[Address]])
async def get_multisig_owners(
    chain: Chain,
    multisig_address: Address,
    container: Container = Depends(get_container),
):
    try:
        multisig = container.multi_sig_service
        owners = await multisig.get_owners(multisig_address)
        return APIResponse.success(data=owners)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/multisig/{multisig_address}/threshold", response_model=APIResponse[int])
async def get_multisig_threshold(
    chain: Chain,
    multisig_address: Address,
    container: Container = Depends(get_container),
):
    try:
        multisig = container.multi_sig_service
        threshold = await multisig.get_threshold(multisig_address)
        return APIResponse.success(data=threshold)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/{chain}/multisig/{multisig_address}/transaction/{tx_id}", response_model=APIResponse[Optional[Dict[str, Any]]])
async def get_multisig_transaction(
    chain: Chain,
    multisig_address: Address,
    tx_id: str,
    container: Container = Depends(get_container),
):
    try:
        multisig = container.multi_sig_service
        tx = await multisig.get_transaction(
            multisig_address=multisig_address,
            tx_id=tx_id,
        )
        return APIResponse.success(data=tx)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
