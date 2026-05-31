from datetime import datetime
from typing import Any, Dict, List, Optional
from pydantic import Field

from .common import BaseModel


class WalletCreateRequest(BaseModel):
    name: str
    passphrase: str = ""
    mnemonic: Optional[str] = None
    store_mnemonic: bool = True


class WalletResponse(BaseModel):
    wallet_id: str
    name: str
    master_xpub: str
    network: str
    depth: int
    mnemonic: Optional[str] = None
    created_at: datetime


class AddressDeriveRequest(BaseModel):
    wallet_id: str
    start_index: int = 0
    count: int = 1
    include_private_keys: bool = False


class AddressResponse(BaseModel):
    address: str
    path: str
    index: int
    public_key: str
    private_key: Optional[str] = None
    label: Optional[str] = None
    tags: List[str] = Field(default_factory=list)


class AddressBookEntryCreateRequest(BaseModel):
    address: str
    chain: str
    label: Optional[str] = None
    tags: List[str] = Field(default_factory=list)
    is_own: bool = False
    wallet_id: Optional[str] = None
    path: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)


class AddressBookEntryResponse(BaseModel):
    entry_id: str
    address: str
    chain: str
    label: Optional[str]
    tags: List[str]
    is_own: bool
    wallet_id: Optional[str]
    path: Optional[str]
    metadata: Dict[str, Any]
    created_at: datetime
    updated_at: datetime
