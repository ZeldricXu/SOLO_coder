from __future__ import annotations

from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException

from src.shared.container import Container, container
from src.shared.types import (
    APIResponse,
    HexString,
    ZKPProof,
    ZKPVerificationResult,
)

router = APIRouter(prefix="/zkp", tags=["zkp"])


async def get_container() -> Container:
    return container


@router.post("/verify", response_model=APIResponse[ZKPVerificationResult])
async def verify_proof(
    proof: ZKPProof,
    container: Container = Depends(get_container),
):
    try:
        verifier = container.zkp_verifier
        result = await verifier.verify_proof(proof)
        return APIResponse.success(data=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/verify/onchain", response_model=APIResponse[ZKPVerificationResult])
async def verify_proof_onchain(
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        verifier = container.zkp_verifier
        proof = ZKPProof(**request["proof"])
        result = await verifier.verify_proof_on_chain(
            proof=proof,
            verifier_address=request["verifier_address"],
        )
        return APIResponse.success(data=result)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/circuit", response_model=APIResponse[bool])
async def register_circuit(
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        verifier = container.zkp_verifier
        success = await verifier.register_circuit(
            circuit_id=request["circuit_id"],
            verification_key=request["verification_key"],
            circuit_type=request.get("circuit_type", "groth16"),
            metadata=request.get("metadata"),
        )
        return APIResponse.success(data=success)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/circuit/{circuit_id}", response_model=APIResponse[bool])
async def unregister_circuit(
    circuit_id: str,
    container: Container = Depends(get_container),
):
    try:
        verifier = container.zkp_verifier
        success = await verifier.unregister_circuit(circuit_id)
        return APIResponse.success(data=success)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/circuits", response_model=APIResponse[List[str]])
async def list_circuits(
    container: Container = Depends(get_container),
):
    try:
        verifier = container.zkp_verifier
        circuits = await verifier.get_registered_circuits()
        return APIResponse.success(data=circuits)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/circuit/{circuit_id}", response_model=APIResponse[Optional[Dict[str, Any]]])
async def get_circuit_info(
    circuit_id: str,
    container: Container = Depends(get_container),
):
    try:
        verifier = container.zkp_verifier
        info = await verifier.get_circuit_info(circuit_id)
        return APIResponse.success(data=info)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/validate", response_model=APIResponse[bool])
async def validate_proof_format(
    proof: ZKPProof,
    container: Container = Depends(get_container),
):
    try:
        verifier = container.zkp_verifier
        valid = await verifier.validate_proof_format(proof)
        return APIResponse.success(data=valid)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
