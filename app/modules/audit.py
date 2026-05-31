from datetime import datetime
from typing import Any, Dict, List, Optional
from dataclasses import dataclass, field
from enum import Enum
import hashlib
import json
from pathlib import Path
import uuid

from app.core.logger import logger
from app.core.events import event_bus, EventType, build_event
from app.core.config import settings
from app.core.models import AuditLogEntry


class LogStatus(str, Enum):
    PENDING = "pending"
    COMMITTED = "committed"
    VERIFIED = "verified"
    CORRUPTED = "corrupted"


@dataclass
class HashChainLink:
    index: int
    log_id: str
    timestamp: datetime
    previous_hash: str
    current_hash: str
    data: Dict[str, Any]
    status: LogStatus = LogStatus.PENDING


class HashChainStorage:
    def __init__(self, storage_dir: Optional[str] = None):
        self._storage_dir = Path(storage_dir) if storage_dir else Path(settings.audit_log_path)
        self._storage_dir.mkdir(parents=True, exist_ok=True)
        self._chain_file = self._storage_dir / "audit_chain.json"
        self._chain: List[HashChainLink] = []
        self._last_hash: str = "0" * 64
        self._load_chain()

    def _load_chain(self):
        if self._chain_file.exists():
            try:
                with open(self._chain_file, "r", encoding="utf-8") as f:
                    data = json.load(f)
                self._chain = [
                    HashChainLink(
                        index=link["index"],
                        log_id=link["log_id"],
                        timestamp=datetime.fromisoformat(link["timestamp"]),
                        previous_hash=link["previous_hash"],
                        current_hash=link["current_hash"],
                        data=link["data"],
                        status=link.get("status", LogStatus.COMMITTED)
                    )
                    for link in data
                ]
                if self._chain:
                    self._last_hash = self._chain[-1].current_hash
                    logger.info(f"Loaded audit chain with {len(self._chain)} entries")
            except Exception as e:
                logger.error(f"Failed to load audit chain: {e}")
                self._chain = []

    def _save_chain(self):
        try:
            data = [
                {
                    "index": link.index,
                    "log_id": link.log_id,
                    "timestamp": link.timestamp.isoformat(),
                    "previous_hash": link.previous_hash,
                    "current_hash": link.current_hash,
                    "data": link.data,
                    "status": link.status
                }
                for link in self._chain
            ]
            with open(self._chain_file, "w", encoding="utf-8") as f:
                json.dump(data, f, indent=2, default=str)
        except Exception as e:
            logger.error(f"Failed to save audit chain: {e}")
            raise

    def _compute_hash(self, data: Dict[str, Any], previous_hash: str, timestamp: datetime) -> str:
        content = json.dumps({
            "data": data,
            "previous_hash": previous_hash,
            "timestamp": timestamp.isoformat()
        }, sort_keys=True)
        return hashlib.sha256(content.encode("utf-8")).hexdigest()

    def append(self, log_entry: AuditLogEntry) -> HashChainLink:
        link = HashChainLink(
            index=len(self._chain),
            log_id=log_entry.log_id,
            timestamp=log_entry.timestamp,
            previous_hash=self._last_hash,
            current_hash="",
            data={
                "actor": log_entry.actor,
                "action": log_entry.action,
                "resource_type": log_entry.resource_type,
                "resource_id": log_entry.resource_id,
                "details": log_entry.details,
                "status": log_entry.status
            },
            status=LogStatus.PENDING
        )

        link.current_hash = self._compute_hash(link.data, link.previous_hash, link.timestamp)
        link.status = LogStatus.COMMITTED

        self._chain.append(link)
        self._last_hash = link.current_hash
        self._save_chain()

        log_entry.previous_hash = link.previous_hash
        log_entry.current_hash = link.current_hash

        event_bus.emit(build_event(EventType.AUDIT_LOGGED, {
            "log_id": log_entry.log_id,
            "action": log_entry.action,
            "hash": link.current_hash
        }))

        logger.debug(f"Audit log appended: {log_entry.log_id}")
        return link

    def get_chain(self) -> List[HashChainLink]:
        return self._chain.copy()

    def get_by_log_id(self, log_id: str) -> Optional[HashChainLink]:
        for link in self._chain:
            if link.log_id == log_id:
                return link
        return None

    def get_by_index(self, index: int) -> Optional[HashChainLink]:
        if 0 <= index < len(self._chain):
            return self._chain[index]
        return None

    def get_by_action(self, action: str) -> List[HashChainLink]:
        return [link for link in self._chain if link.data.get("action") == action]

    def get_by_resource(self, resource_type: str, resource_id: Optional[str] = None) -> List[HashChainLink]:
        results = []
        for link in self._chain:
            if link.data.get("resource_type") == resource_type:
                if resource_id is None or link.data.get("resource_id") == resource_id:
                    results.append(link)
        return results

    def get_by_time_range(self, start: datetime, end: datetime) -> List[HashChainLink]:
        return [
            link for link in self._chain
            if start <= link.timestamp <= end
        ]

    def get_last_hash(self) -> str:
        return self._last_hash

    def length(self) -> int:
        return len(self._chain)


class IntegrityVerifier:
    def __init__(self, chain_storage: HashChainStorage):
        self._chain = chain_storage
        self._known_good_hashes: Dict[int, str] = {}

    def verify_link(self, link: HashChainLink) -> bool:
        computed_hash = self._chain._compute_hash(link.data, link.previous_hash, link.timestamp)
        return computed_hash == link.current_hash

    def verify_chain(self, start_index: int = 0, end_index: Optional[int] = None) -> Dict[str, Any]:
        chain = self._chain.get_chain()
        if not chain:
            return {"valid": True, "verified_count": 0, "corrupted_indices": [], "message": "空链"}

        end = end_index if end_index is not None else len(chain) - 1
        corrupted: List[int] = []
        verified = 0

        if start_index < 0 or end >= len(chain):
            return {"valid": False, "verified_count": 0, "corrupted_indices": [], "message": "索引范围无效"}

        previous_hash = chain[start_index].previous_hash if start_index > 0 else "0" * 64
        if start_index > 0:
            previous_hash = chain[start_index - 1].current_hash

        for i in range(start_index, end + 1):
            link = chain[i]
            if link.previous_hash != previous_hash:
                corrupted.append(i)
                continue

            if self.verify_link(link):
                verified += 1
                previous_hash = link.current_hash
                self._known_good_hashes[i] = link.current_hash
            else:
                corrupted.append(i)
                previous_hash = link.current_hash

        return {
            "valid": len(corrupted) == 0,
            "verified_count": verified,
            "corrupted_indices": corrupted,
            "total_count": end - start_index + 1,
            "message": "链完整" if len(corrupted) == 0 else f"检测到 {len(corrupted)} 个损坏条目"
        }

    def verify_entry(self, log_id: str) -> Dict[str, Any]:
        link = self._chain.get_by_log_id(log_id)
        if not link:
            return {"valid": False, "exists": False, "message": "日志条目不存在"}

        chain_valid = self.verify_chain(start_index=max(0, link.index - 1), end_index=link.index)
        link_valid = self.verify_link(link)

        return {
            "valid": chain_valid["valid"] and link_valid,
            "exists": True,
            "log_id": log_id,
            "index": link.index,
            "hash_valid": link_valid,
            "chain_valid": chain_valid["valid"],
            "message": "条目有效且链完整" if (chain_valid["valid"] and link_valid) else "条目或链存在问题"
        }

    def detect_tampering(self) -> Dict[str, Any]:
        result = self.verify_chain()
        if not result["valid"]:
            corrupted_links = []
            for idx in result["corrupted_indices"]:
                link = self._chain.get_by_index(idx)
                if link:
                    corrupted_links.append({
                        "index": idx,
                        "log_id": link.log_id,
                        "action": link.data.get("action"),
                        "timestamp": link.timestamp.isoformat()
                    })
            result["corrupted_links"] = corrupted_links

        return result

    def export_proof(self, log_id: str) -> Optional[Dict[str, Any]]:
        link = self._chain.get_by_log_id(log_id)
        if not link:
            return None

        proof = {
            "log_id": log_id,
            "index": link.index,
            "timestamp": link.timestamp.isoformat(),
            "current_hash": link.current_hash,
            "previous_hash": link.previous_hash,
            "data": link.data,
            "verification": self.verify_entry(log_id),
            "chain_length": self._chain.length()
        }
        return proof


class AuditLogModule:
    def __init__(self):
        self._chain_storage = HashChainStorage()
        self._integrity_verifier = IntegrityVerifier(self._chain_storage)
        logger.info("AuditLogModule initialized")

    @property
    def chain_storage(self) -> HashChainStorage:
        return self._chain_storage

    @property
    def integrity_verifier(self) -> IntegrityVerifier:
        return self._integrity_verifier

    def log(self, actor: str, action: str, resource_type: str,
            resource_id: Optional[str] = None, details: Optional[Dict[str, Any]] = None,
            status: str = "success") -> AuditLogEntry:
        entry = AuditLogEntry(
            actor=actor,
            action=action,
            resource_type=resource_type,
            resource_id=resource_id,
            details=details or {},
            status=status,
            previous_hash="",
            current_hash=""
        )
        self._chain_storage.append(entry)
        return entry

    def query_logs(self, action: Optional[str] = None,
                   resource_type: Optional[str] = None,
                   resource_id: Optional[str] = None,
                   start_time: Optional[datetime] = None,
                   end_time: Optional[datetime] = None,
                   limit: int = 100) -> List[AuditLogEntry]:
        links = self._chain_storage.get_chain()

        if action:
            links = [l for l in links if l.data.get("action") == action]
        if resource_type:
            links = [l for l in links if l.data.get("resource_type") == resource_type]
        if resource_id:
            links = [l for l in links if l.data.get("resource_id") == resource_id]
        if start_time:
            links = [l for l in links if l.timestamp >= start_time]
        if end_time:
            links = [l for l in links if l.timestamp <= end_time]

        links = links[-limit:]

        return [
            AuditLogEntry(
                log_id=l.log_id,
                timestamp=l.timestamp,
                actor=l.data.get("actor", ""),
                action=l.data.get("action", ""),
                resource_type=l.data.get("resource_type", ""),
                resource_id=l.data.get("resource_id"),
                details=l.data.get("details", {}),
                status=l.data.get("status", "success"),
                previous_hash=l.previous_hash,
                current_hash=l.current_hash
            )
            for l in links
        ]

    def verify_integrity(self) -> Dict[str, Any]:
        return self._integrity_verifier.detect_tampering()

    def get_log_proof(self, log_id: str) -> Optional[Dict[str, Any]]:
        return self._integrity_verifier.export_proof(log_id)

    def get_chain_info(self) -> Dict[str, Any]:
        return {
            "length": self._chain_storage.length(),
            "last_hash": self._chain_storage.get_last_hash(),
            "last_entry": {
                "log_id": self._chain_storage.get_chain()[-1].log_id if self._chain_storage.get_chain() else None,
                "timestamp": self._chain_storage.get_chain()[-1].timestamp.isoformat() if self._chain_storage.get_chain() else None
            }
        }


audit_module = AuditLogModule()
