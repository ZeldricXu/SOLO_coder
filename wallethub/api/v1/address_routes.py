from fastapi import APIRouter, HTTPException
from typing import List

from wallethub.api.models.address_models import (
    WalletCreateRequest,
    WalletResponse,
    AddressDeriveRequest,
    AddressResponse,
    AddressBookEntryCreateRequest,
    AddressBookEntryResponse,
)
from wallethub.core import AddressError

router = APIRouter(prefix="/addresses", tags=["Addresses"])


@router.post("/wallets", response_model=WalletResponse, status_code=201)
async def create_wallet(request: WalletCreateRequest):
    try:
        from wallethub.modules.address_manager import HDWalletManager

        manager = HDWalletManager()

        if request.mnemonic:
            wallet = manager.import_wallet(
                name=request.name,
                mnemonic=request.mnemonic,
                passphrase=request.passphrase,
            )
        else:
            wallet = manager.create_wallet(
                name=request.name,
                passphrase=request.passphrase,
                store_mnemonic=request.store_mnemonic,
            )

        return WalletResponse(
            wallet_id=wallet["wallet_id"],
            name=wallet["name"],
            master_xpub=wallet["master_xpub"],
            network="ethereum",
            depth=0,
            mnemonic=wallet.get("mnemonic"),
            created_at=wallet["created_at"],
        )
    except AddressError as e:
        raise HTTPException(status_code=400, detail=e.message)


@router.get("/wallets", response_model=List[WalletResponse])
async def list_wallets():
    from wallethub.modules.address_manager import HDWalletManager

    manager = HDWalletManager()
    wallets = manager.list_wallets()

    return [
        WalletResponse(
            wallet_id=w["wallet_id"],
            name=w["name"],
            master_xpub=w["master_xpub"],
            network=w["network"],
            depth=w["depth"],
            created_at=w["created_at"],
        )
        for w in wallets
    ]


@router.get("/wallets/{wallet_id}", response_model=WalletResponse)
async def get_wallet(wallet_id: str):
    from wallethub.modules.address_manager import HDWalletManager

    manager = HDWalletManager()
    wallet = manager.get_wallet(wallet_id)

    if not wallet:
        raise HTTPException(status_code=404, detail="Wallet not found")

    return WalletResponse(
        wallet_id=wallet["wallet_id"],
        name=wallet["name"],
        master_xpub=wallet["master_xpub"],
        network=wallet["network"],
        depth=wallet["depth"],
        created_at=wallet["created_at"],
    )


@router.post("/wallets/{wallet_id}/derive", response_model=List[AddressResponse])
async def derive_addresses(wallet_id: str, request: AddressDeriveRequest):
    try:
        from wallethub.modules.address_manager import HDWalletManager

        manager = HDWalletManager()
        addresses = manager.derive_addresses(
            wallet_id=wallet_id,
            start_index=request.start_index,
            count=request.count,
            include_private_keys=request.include_private_keys,
        )

        return [
            AddressResponse(
                address=addr.address,
                path=addr.path,
                index=addr.index,
                public_key=addr.public_key,
                private_key=addr.private_key,
                label=addr.label,
                tags=addr.tags,
            )
            for addr in addresses
        ]
    except AddressError as e:
        raise HTTPException(status_code=400, detail=e.message)


@router.post("/book", response_model=AddressBookEntryResponse, status_code=201)
async def add_address_book_entry(request: AddressBookEntryCreateRequest):
    try:
        from wallethub.modules.address_manager import AddressBook

        book = AddressBook()
        entry = book.add_entry(
            address=request.address,
            chain=request.chain,
            label=request.label,
            tags=request.tags,
            is_own=request.is_own,
            wallet_id=request.wallet_id,
            path=request.path,
            metadata=request.metadata,
        )

        return AddressBookEntryResponse(
            entry_id=entry.entry_id,
            address=entry.address,
            chain=entry.chain,
            label=entry.label,
            tags=entry.tags,
            is_own=entry.is_own,
            wallet_id=entry.wallet_id,
            path=entry.path,
            metadata=entry.metadata,
            created_at=entry.created_at,
            updated_at=entry.updated_at,
        )
    except AddressError as e:
        raise HTTPException(status_code=400, detail=e.message)


@router.get("/book", response_model=List[AddressBookEntryResponse])
async def list_address_book(
    chain: str = None,
    is_own: bool = None,
    tags: str = None,
):
    from wallethub.modules.address_manager import AddressBook

    book = AddressBook()
    tag_list = tags.split(",") if tags else None
    entries = book.list_entries(chain=chain, is_own=is_own, tags=tag_list)

    return [
        AddressBookEntryResponse(
            entry_id=e.entry_id,
            address=e.address,
            chain=e.chain,
            label=e.label,
            tags=e.tags,
            is_own=e.is_own,
            wallet_id=e.wallet_id,
            path=e.path,
            metadata=e.metadata,
            created_at=e.created_at,
            updated_at=e.updated_at,
        )
        for e in entries
    ]


@router.get("/book/{entry_id}", response_model=AddressBookEntryResponse)
async def get_address_book_entry(entry_id: str):
    from wallethub.modules.address_manager import AddressBook

    book = AddressBook()
    entry = book.get_entry(entry_id)

    if not entry:
        raise HTTPException(status_code=404, detail="Entry not found")

    return AddressBookEntryResponse(
        entry_id=entry.entry_id,
        address=entry.address,
        chain=entry.chain,
        label=entry.label,
        tags=entry.tags,
        is_own=entry.is_own,
        wallet_id=entry.wallet_id,
        path=entry.path,
        metadata=entry.metadata,
        created_at=entry.created_at,
        updated_at=entry.updated_at,
    )


@router.delete("/book/{entry_id}")
async def delete_address_book_entry(entry_id: str):
    from wallethub.modules.address_manager import AddressBook

    book = AddressBook()
    book.delete_entry(entry_id)
    return {"message": "Entry deleted"}


@router.get("/validate/{address}")
async def validate_address(address: str):
    from wallethub.modules.address_manager import HDWalletManager

    is_valid = HDWalletManager.is_valid_address(address)
    return {"address": address, "is_valid": is_valid}
