from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
from datetime import datetime, timezone

from eth_utils import to_checksum_address

from wallethub.core import AddressError
from wallethub.utils import generate_id


@dataclass
class AddressBookEntry:
    entry_id: str
    address: str
    chain: str
    label: Optional[str] = None
    tags: List[str] = field(default_factory=list)
    is_own: bool = False
    wallet_id: Optional[str] = None
    path: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)
    created_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = field(default_factory=lambda: datetime.now(timezone.utc))


class AddressBook:
    def __init__(self):
        self._entries: Dict[str, AddressBookEntry] = {}
        self._address_index: Dict[str, List[str]] = {}

    def add_entry(
        self,
        address: str,
        chain: str,
        label: Optional[str] = None,
        tags: Optional[List[str]] = None,
        is_own: bool = False,
        wallet_id: Optional[str] = None,
        path: Optional[str] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> AddressBookEntry:
        try:
            normalized_address = to_checksum_address(address)
        except Exception:
            raise AddressError(f"Invalid address: {address}")

        entry_id = generate_id("addr")
        entry = AddressBookEntry(
            entry_id=entry_id,
            address=normalized_address,
            chain=chain,
            label=label,
            tags=tags or [],
            is_own=is_own,
            wallet_id=wallet_id,
            path=path,
            metadata=metadata or {},
        )

        self._entries[entry_id] = entry

        addr_key = f"{chain}:{normalized_address.lower()}"
        if addr_key not in self._address_index:
            self._address_index[addr_key] = []
        self._address_index[addr_key].append(entry_id)

        return entry

    def get_entry(self, entry_id: str) -> Optional[AddressBookEntry]:
        return self._entries.get(entry_id)

    def find_by_address(
        self,
        address: str,
        chain: Optional[str] = None,
    ) -> List[AddressBookEntry]:
        try:
            normalized_address = to_checksum_address(address)
        except Exception:
            return []

        results = []
        for entry in self._entries.values():
            if entry.address.lower() == normalized_address.lower():
                if chain is None or entry.chain == chain:
                    results.append(entry)
        return results

    def update_entry(
        self,
        entry_id: str,
        label: Optional[str] = None,
        tags: Optional[List[str]] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> AddressBookEntry:
        entry = self._entries.get(entry_id)
        if not entry:
            raise AddressError(f"Entry {entry_id} not found")

        if label is not None:
            entry.label = label
        if tags is not None:
            entry.tags = tags
        if metadata is not None:
            entry.metadata.update(metadata)

        entry.updated_at = datetime.now(timezone.utc)
        return entry

    def add_tags(self, entry_id: str, tags: List[str]) -> AddressBookEntry:
        entry = self._entries.get(entry_id)
        if not entry:
            raise AddressError(f"Entry {entry_id} not found")

        for tag in tags:
            if tag not in entry.tags:
                entry.tags.append(tag)

        entry.updated_at = datetime.now(timezone.utc)
        return entry

    def remove_tags(self, entry_id: str, tags: List[str]) -> AddressBookEntry:
        entry = self._entries.get(entry_id)
        if not entry:
            raise AddressError(f"Entry {entry_id} not found")

        entry.tags = [t for t in entry.tags if t not in tags]
        entry.updated_at = datetime.now(timezone.utc)
        return entry

    def delete_entry(self, entry_id: str) -> None:
        entry = self._entries.pop(entry_id, None)
        if entry:
            addr_key = f"{entry.chain}:{entry.address.lower()}"
            if addr_key in self._address_index:
                self._address_index[addr_key] = [
                    eid for eid in self._address_index[addr_key] if eid != entry_id
                ]
                if not self._address_index[addr_key]:
                    del self._address_index[addr_key]

    def list_entries(
        self,
        chain: Optional[str] = None,
        is_own: Optional[bool] = None,
        tags: Optional[List[str]] = None,
        wallet_id: Optional[str] = None,
    ) -> List[AddressBookEntry]:
        entries = list(self._entries.values())

        if chain:
            entries = [e for e in entries if e.chain == chain]
        if is_own is not None:
            entries = [e for e in entries if e.is_own == is_own]
        if wallet_id:
            entries = [e for e in entries if e.wallet_id == wallet_id]
        if tags:
            entries = [e for e in entries if all(t in e.tags for t in tags)]

        return sorted(entries, key=lambda e: e.created_at, reverse=True)

    def get_all_tags(self) -> List[str]:
        all_tags = set()
        for entry in self._entries.values():
            all_tags.update(entry.tags)
        return sorted(list(all_tags))

    def search(self, query: str) -> List[AddressBookEntry]:
        query_lower = query.lower()
        results = []

        for entry in self._entries.values():
            if (
                query_lower in entry.address.lower()
                or (entry.label and query_lower in entry.label.lower())
                or any(query_lower in t.lower() for t in entry.tags)
            ):
                results.append(entry)

        return results

    def export(self, format: str = "json") -> Dict[str, Any]:
        entries = [
            {
                "address": e.address,
                "chain": e.chain,
                "label": e.label,
                "tags": e.tags,
                "is_own": e.is_own,
                "metadata": e.metadata,
            }
            for e in self._entries.values()
        ]

        return {
            "format": format,
            "version": "1.0",
            "exported_at": datetime.now(timezone.utc).isoformat(),
            "entries": entries,
        }
