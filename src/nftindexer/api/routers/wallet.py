from typing import Any, Dict, List, Optional
from fastapi import APIRouter, HTTPException, Query
from pydantic import BaseModel

from ...core.schemas import ResourceResponse
from ...utils import get_logger
from ..deps import HDWalletModuleDep, TraceIdDep, ApiKeyDep

logger = get_logger(__name__)
router = APIRouter(prefix="/api/v1/wallet", tags=["HD Wallet"])


class DeriveAddressRequest(BaseModel):
    mnemonic: Optional[str] = None
    chain_id: int = 1
    index: int = 0
    count: int = 1
    derivation_path: Optional[str] = None


class ImportAddressRequest(BaseModel):
    address: str
    chain_id: int = 1
    public_key: Optional[str] = None
    metadata: Dict[str, Any] = {}


class CreateTagRequest(BaseModel):
    address: str
    chain_id: int = 1
    tag: str
    label: str
    category: str = "general"


class SignMessageRequest(BaseModel):
    address_id: str
    message: str


class VerifySignatureRequest(BaseModel):
    address: str
    message: str
    signature: str


@router.post("/mnemonic", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def generate_mnemonic(
    hd_wallet: HDWalletModuleDep,
    trace_id: TraceIdDep,
):
    try:
        mnemonic = hd_wallet.generate_mnemonic()
        return ResourceResponse(
            code=200,
            message="Mnemonic generated successfully",
            request_id=trace_id,
            data={"mnemonic": mnemonic},
        )
    except Exception as e:
        logger.error(f"Error generating mnemonic: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/derive", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def derive_addresses(
    request: DeriveAddressRequest,
    hd_wallet: HDWalletModuleDep,
    trace_id: TraceIdDep,
):
    try:
        addresses = await hd_wallet.derive_addresses(
            mnemonic=request.mnemonic,
            chain_id=request.chain_id,
            start_index=request.index,
            count=request.count,
            derivation_path=request.derivation_path,
        )
        return ResourceResponse(
            code=201,
            message="Addresses derived successfully",
            request_id=trace_id,
            data={"addresses": addresses, "count": len(addresses)},
        )
    except Exception as e:
        logger.error(f"Error deriving addresses: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/import", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def import_address(
    request: ImportAddressRequest,
    hd_wallet: HDWalletModuleDep,
    trace_id: TraceIdDep,
):
    try:
        address = await hd_wallet.import_address(
            address=request.address,
            chain_id=request.chain_id,
            public_key=request.public_key,
            metadata=request.metadata,
        )
        return ResourceResponse(
            code=201,
            message="Address imported successfully",
            request_id=trace_id,
            data=address,
        )
    except Exception as e:
        logger.error(f"Error importing address: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/addresses", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def list_addresses(
    chain_id: Optional[int] = None,
    tag: Optional[str] = None,
    is_used: Optional[bool] = None,
    limit: int = Query(20, ge=1, le=100),
    offset: int = Query(0, ge=0),
    hd_wallet: HDWalletModuleDep = ...,
    trace_id: TraceIdDep = ...,
):
    try:
        addresses = await hd_wallet.list_addresses(
            chain_id=chain_id,
            tag=tag,
            is_used=is_used,
            limit=limit,
            offset=offset,
        )
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data={"addresses": addresses, "total": len(addresses)},
        )
    except Exception as e:
        logger.error(f"Error listing addresses: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/addresses/{address_id}", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_address(
    address_id: str,
    hd_wallet: HDWalletModuleDep,
    trace_id: TraceIdDep,
):
    try:
        address = await hd_wallet.get_address(address_id)
        if not address:
            raise HTTPException(status_code=404, detail="Address not found")
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=address,
        )
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Error getting address {address_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/addresses/{address_id}/balance", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def get_address_balance(
    address_id: str,
    hd_wallet: HDWalletModuleDep,
    trace_id: TraceIdDep,
):
    try:
        balance = await hd_wallet.get_address_balance(address_id)
        return ResourceResponse(
            code=200,
            message="success",
            request_id=trace_id,
            data=balance,
        )
    except Exception as e:
        logger.error(f"Error getting balance for address {address_id}: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/tags", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def create_address_tag(
    request: CreateTagRequest,
    hd_wallet: HDWalletModuleDep,
    trace_id: TraceIdDep,
):
    try:
        tag = await hd_wallet.create_address_tag(
            address=request.address,
            chain_id=request.chain_id,
            tag=request.tag,
            label=request.label,
            category=request.category,
        )
        return ResourceResponse(
            code=201,
            message="Tag created successfully",
            request_id=trace_id,
            data=tag,
        )
    except Exception as e:
        logger.error(f"Error creating address tag: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/sign/message", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def sign_message(
    request: SignMessageRequest,
    hd_wallet: HDWalletModuleDep,
    trace_id: TraceIdDep,
):
    try:
        signature = await hd_wallet.sign_message(
            address_id=request.address_id,
            message=request.message,
        )
        return ResourceResponse(
            code=200,
            message="Message signed successfully",
            request_id=trace_id,
            data={"signature": signature},
        )
    except Exception as e:
        logger.error(f"Error signing message: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/verify/signature", response_model=ResourceResponse, dependencies=[ApiKeyDep])
async def verify_signature(
    request: VerifySignatureRequest,
    hd_wallet: HDWalletModuleDep,
    trace_id: TraceIdDep,
):
    try:
        valid = hd_wallet.verify_signature(
            address=request.address,
            message=request.message,
            signature=request.signature,
        )
        return ResourceResponse(
            code=200,
            message="Verification complete",
            request_id=trace_id,
            data={"valid": valid},
        )
    except Exception as e:
        logger.error(f"Error verifying signature: {e}")
        raise HTTPException(status_code=500, detail=str(e))
