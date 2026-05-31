from typing import Any, Dict, List, Optional
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from ...core.schemas import ResourceResponse
from ...utils import get_logger
from ..deps import ZKPVerifierModuleDep, TraceIdDep, ApiKeyDep

logger = get_logger(__name__)
router = APIRouter(prefix="/api/v1/zkp", tags=["Zero-Knowledge Proof"])


class VerifyProofRequest(BaseModel):
    circuit_id: str
    proof_system: str = "groth16"
    proof_data: Dict[str, Any]
    public_inputs: List[str] = []
    verification_key_hash: Optional[str] = None


class RegisterVerificationKeyRequest(BaseModel):
    circuit_id: str
    proof_system: str
    verification_key: Dict[str, Any]


class BatchVerifyRequest(BaseModel):
    proofs: List[VerifyProofRequest]


@router.post("/verify", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def verify_proof(
    request: VerifyProofRequest,
    zkp_verifier: ZKPVerifierModuleDep,
    trace_id: TraceIdDep,
):
    try:
        result = await zkp_verifier.verify_proof(
            circuit_id=request.circuit_id,
            proof_system=request.proof_system,
            proof_data=request.proof_data,
            public_inputs=request.public_inputs,
            verification_key_hash=request.verification_key_hash,
        )
        return ResourceResponse(
            code=200,
            message="Verification complete",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error verifying ZKP proof: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/batch-verify", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def batch_verify(
    request: BatchVerifyRequest,
    zkp_verifier: ZKPVerifierModuleDep,
    trace_id: TraceIdDep,
):
    try:
        results = await zkp_verifier.batch_verify(
            proofs=[
                {
                    "circuit_id": p.circuit_id,
                    "proof_system": p.proof_system,
                    "proof_data": p.proof_data,
                    "public_inputs": p.public_inputs,
                    "verification_key_hash": p.verification_key_hash,
                }
                for p in request.proofs
            ]
        )
        return ResourceResponse(
            code=200,
            message="Batch verification complete",
            request_id=trace_id,
            data={"results": results, "total": len(results)},
        )
    except Exception as e:
        logger.error(f"Error batch verifying ZKP proofs: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/proofs", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def list_proofs(
    circuit_id: Optional[str] = None,
    is_valid: Optional[bool] = None,
    proof_system: Optional[str] = None,
    limit: int = Query(20, ge=1, le=100),
    offset: int = Query(0, ge=0),
    zkp_verifier: ZKPVerifierModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        proofs = await zkp_verifier.list_proofs(
            circuit_id=circuit_id,
            is_valid=is_valid,
            proof_system=proof_system,
            limit=limit,
            offset=offset,
        )
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={"proofs": proofs, "total": len(proofs)},
        )
    except Exception as e:
        logger.error(f"Error listing ZKP proofs: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/proofs/{proof_id}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_proof(
    proof_id: str,
    zkp_verifier: ZKPVerifierModuleDep,
    trace_id: TraceIdDep,
):
    try:
        proof = await zkp_verifier.get_proof(proof_id)
        if not proof:
            raise HTTPException(status_code=404, detail="Proof not found")
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=proof,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting ZKP proof {proof_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/verification-keys", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def register_verification_key(
    request: RegisterVerificationKeyRequest,
    zkp_verifier: ZKPVerifierModuleDep,
    trace_id: TraceIdDep,
):
    try:
        result = await zkp_verifier.register_verification_key(
            circuit_id=request.circuit_id,
            proof_system=request.proof_system,
            verification_key=request.verification_key,
        )
        return ResourceResponse(
            code=201,
            message="Verification key registered successfully",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error registering verification key: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/verify-on-chain", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def verify_on_chain(
    chain_id: int,
    verifier_contract: str,
    proof_data: Dict[str, Any],
    zkp_verifier: ZKPVerifierModuleDep,
    trace_id: TraceIdDep,
):
    try:
        result = await zkp_verifier.verify_on_chain(
            chain_id=chain_id,
            verifier_contract=verifier_contract,
            proof_data=proof_data,
        )
        return ResourceResponse(
            code=200,
            message="On-chain verification complete",
            request_id=trace_id,
            data=result,
        )
    except Exception as e:
        logger.error(f"Error verifying ZKP proof on-chain: {e}")
        raise HTTPException(status_code=500, detail=str(e))
