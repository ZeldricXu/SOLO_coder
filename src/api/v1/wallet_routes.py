from __future__ import annotations

from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query

from src.shared.container import Container, container
from src.shared.types import (
    Address,
    APIResponse,
    Chain,
    HDWalletAccount,
    HexString,
)

router = APIRouter(prefix="/wallet", tags=["wallet"])


async def get_container() -> Container:
    return container


@router.post("/mnemonic", response_model=APIResponse[str])
async def generate_mnemonic(
    strength: int = Query(128, ge=128, le=256),
    container: Container = Depends(get_container),
):
    try:
        wallet = container.hd_wallet
        mnemonic = await wallet.generate_mnemonic(strength)
        return APIResponse.success(data=mnemonic)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/create", response_model=APIResponse[bool])
async def create_wallet(
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        wallet = container.hd_wallet
        success = await wallet.create_wallet_from_mnemonic(
            mnemonic=request["mnemonic"],
            passphrase=request.get("passphrase"),
            hd_path=request.get("hd_path"),
        )
        return APIResponse.success(data=success)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/status", response_model=APIResponse[bool])
async def get_wallet_status(
    container: Container = Depends(get_container),
):
    try:
        wallet = container.hd_wallet
        return APIResponse.success(data=wallet.is_initialized())
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/derive", response_model=APIResponse[HDWalletAccount])
async def derive_address(
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        wallet = container.hd_wallet
        account = await wallet.derive_address(
            index=request["index"],
            hd_path=request.get("hd_path"),
            label=request.get("label"),
            tags=request.get("tags"),
        )
        return APIResponse.success(data=account)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/derive/next", response_model=APIResponse[HDWalletAccount])
async def derive_next_address(
    request: Optional[Dict[str, Any]] = None,
    container: Container = Depends(get_container),
):
    try:
        wallet = container.hd_wallet
        request = request or {}
        account = await wallet.derive_next_address(
            label=request.get("label"),
            tags=request.get("tags"),
        )
        return APIResponse.success(data=account)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/address/{index}", response_model=APIResponse[Optional[HDWalletAccount]])
async def get_address(
    index: int,
    container: Container = Depends(get_container),
):
    try:
        wallet = container.hd_wallet
        account = await wallet.get_address(index)
        return APIResponse.success(data=account)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/addresses", response_model=APIResponse[List[HDWalletAccount]])
async def list_addresses(
    container: Container = Depends(get_container),
):
    try:
        wallet = container.hd_wallet
        accounts = await wallet.list_addresses()
        return APIResponse.success(data=accounts)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/sign", response_model=APIResponse[HexString])
async def sign_message(
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        wallet = container.hd_wallet
        signature = await wallet.sign_message(
            index=request["index"],
            message=request["message"],
        )
        return APIResponse.success(data=signature)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/verify", response_model=APIResponse[bool])
async def verify_signature(
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        wallet = container.hd_wallet
        valid = await wallet.verify_signature(
            address=request["address"],
            message=request["message"],
            signature=request["signature"],
        )
        return APIResponse.success(data=valid)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.post("/addressbook", response_model=APIResponse[str])
async def add_address_book_entry(
    request: Dict[str, Any],
    container: Container = Depends(get_container),
):
    try:
        address_book = container.address_book
        key = await address_book.add_address(
            address=request["address"],
            name=request["name"],
            chain=Chain(request["chain"]),
            labels=request.get("labels"),
            notes=request.get("notes"),
        )
        return APIResponse.success(data=key)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.delete("/addressbook/{address}", response_model=APIResponse[bool])
async def remove_address_book_entry(
    address: Address,
    container: Container = Depends(get_container),
):
    try:
        address_book = container.address_book
        success = await address_book.remove_address(address)
        return APIResponse.success(data=success)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/addressbook/{address}", response_model=APIResponse[Optional[Dict[str, Any]]])
async def get_address_book_entry(
    address: Address,
    container: Container = Depends(get_container),
):
    try:
        address_book = container.address_book
        entry = await address_book.get_address(address)
        return APIResponse.success(data=entry)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))


@router.get("/addressbook", response_model=APIResponse[List[Dict[str, Any]]])
async def list_address_book(
    chain: Optional[str] = None,
    labels: Optional[str] = None,
    container: Container = Depends(get_container),
):
    try:
        address_book = container.address_book
        chain_enum = Chain(chain) if chain else None
        label_list = labels.split(",") if labels else None
        entries = await address_book.list_addresses(chain=chain_enum, labels=label_list)
        return APIResponse.success(data=entries)
    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
