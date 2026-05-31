import json
import threading
from datetime import datetime
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field

from .config import settings
from .utils import generate_id, hash_data


class AuditLogEntry(BaseModel):
    log_id: str = Field(..., description="日志ID")
    sequence: int = Field(..., description="序列号")
    timestamp: datetime = Field(default_factory=datetime.utcnow, description="时间戳")
    action: str = Field(..., description="操作类型")
    actor: str = Field(..., description="操作者")
    resource: str = Field(..., description="资源标识")
    details: Dict[str, Any] = Field(default_factory=dict, description="操作详情")
    previous_hash: str = Field(..., description="前一个哈希")
    current_hash: str = Field(..., description="当前哈希")


class AuditChain:
    def __init__(self):
        self.chain: List[AuditLogEntry] = []
        self._lock = threading.RLock()
        self._initialize_genesis_block()

    def _initialize_genesis_block(self) -> None:
        genesis_details = {"type": "genesis_block"}
        genesis_data = {
            "action": "genesis",
            "actor": "system",
            "resource": "chain_init",
            "details": genesis_details,
            "sequence": 0,
            "previous_hash": "0" * 64
        }
        genesis_hash = hash_data(genesis_data, settings.hash_algorithm)
        entry = AuditLogEntry(
            log_id=generate_id("log_"),
            sequence=0,
            action="genesis",
            actor="system",
            resource="chain_init",
            details=genesis_details,
            previous_hash="0" * 64,
            current_hash=genesis_hash
        )
        self.chain.append(entry)

    def add_entry(self, action: str, actor: str, resource: str, details: Optional[Dict[str, Any]] = None) -> AuditLogEntry:
        with self._lock:
            return self._add_entry_internal(action, actor, resource, details)

    def _add_entry_internal(self, action: str, actor: str, resource: str, details: Optional[Dict[str, Any]] = None) -> AuditLogEntry:
        last_entry = self.chain[-1]
        entry_data = {
            "action": action,
            "actor": actor,
            "resource": resource,
            "details": details or {},
            "sequence": last_entry.sequence + 1,
            "previous_hash": last_entry.current_hash
        }
        current_hash = hash_data(entry_data, settings.hash_algorithm)
        entry = AuditLogEntry(
            log_id=generate_id("log_"),
            sequence=last_entry.sequence + 1,
            action=action,
            actor=actor,
            resource=resource,
            details=details or {},
            previous_hash=last_entry.current_hash,
            current_hash=current_hash
        )
        self.chain.append(entry)
        return entry

    def verify_integrity(self) -> Dict[str, Any]:
        with self._lock:
            return self._verify_integrity_internal()

    def _verify_integrity_internal(self) -> Dict[str, Any]:
        is_valid = True
        errors: List[str] = []

        if len(self.chain) > 0:
            genesis = self.chain[0]
            genesis_hash = hash_data({
                "action": genesis.action,
                "actor": genesis.actor,
                "resource": genesis.resource,
                "details": genesis.details,
                "sequence": 0,
                "previous_hash": "0" * 64
            }, settings.hash_algorithm)
            if genesis.current_hash != genesis_hash:
                is_valid = False
                errors.append("Genesis block: hash verification failed")

        for i in range(1, len(self.chain)):
            current = self.chain[i]
            previous = self.chain[i - 1]

            if current.previous_hash != previous.current_hash:
                is_valid = False
                errors.append(f"Block {current.sequence}: previous_hash mismatch")

            recalculated = hash_data({
                "action": current.action,
                "actor": current.actor,
                "resource": current.resource,
                "details": current.details,
                "sequence": current.sequence,
                "previous_hash": current.previous_hash
            }, settings.hash_algorithm)

            if current.current_hash != recalculated:
                is_valid = False
                errors.append(f"Block {current.sequence}: current_hash verification failed")

        return {
            "is_valid": is_valid,
            "total_blocks": len(self.chain),
            "errors": errors
        }

    def detect_tampering(self, start_sequence: int = 0, end_sequence: Optional[int] = None) -> Dict[str, Any]:
        with self._lock:
            return self._detect_tampering_internal(start_sequence, end_sequence)

    def _detect_tampering_internal(self, start_sequence: int = 0, end_sequence: Optional[int] = None) -> Dict[str, Any]:
        end = end_sequence or len(self.chain)
        tampered_blocks: List[int] = []

        for i in range(max(1, start_sequence), min(end, len(self.chain))):
            current = self.chain[i]
            recalculated = hash_data({
                "action": current.action,
                "actor": current.actor,
                "resource": current.resource,
                "details": current.details,
                "sequence": current.sequence,
                "previous_hash": current.previous_hash
            }, settings.hash_algorithm)

            if current.current_hash != recalculated:
                tampered_blocks.append(current.sequence)

        return {
            "tampered_count": len(tampered_blocks),
            "tampered_sequences": tampered_blocks,
            "scan_range": {"start": start_sequence, "end": end}
        }

    def get_entry_by_sequence(self, sequence: int) -> Optional[AuditLogEntry]:
        for entry in self.chain:
            if entry.sequence == sequence:
                return entry
        return None

    def get_entries_by_action(self, action: str) -> List[AuditLogEntry]:
        return [e for e in self.chain if e.action == action]

    def to_dict(self) -> List[Dict[str, Any]]:
        return [json.loads(e.json()) for e in self.chain]


_audit_chain_instance: Optional[AuditChain] = None


def get_audit_chain() -> AuditChain:
    global _audit_chain_instance
    if _audit_chain_instance is None:
        _audit_chain_instance = AuditChain()
    return _audit_chain_instance
