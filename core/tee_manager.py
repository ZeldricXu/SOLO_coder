import base64
import json
import os
import secrets
from datetime import datetime
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field

from .config import settings
from .utils import generate_id, generate_random_bytes, hash_data


class EnclaveInfo(BaseModel):
    enclave_id: str = Field(..., description="Enclave ID")
    name: str = Field(..., description="Enclave名称")
    status: str = Field(..., description="状态: created, running, paused, destroyed")
    type: str = Field(default="simulated", description="类型: simulated, sgx, sev")
    memory_size: int = Field(default=128, description="内存大小(MB)")
    created_at: str = Field(..., description="创建时间")
    started_at: Optional[str] = Field(None, description="启动时间")
    destroyed_at: Optional[str] = Field(None, description="销毁时间")
    measurement: Optional[str] = Field(None, description="Enclave测量值")
    attributes: Dict[str, Any] = Field(default_factory=dict, description="属性")


class AttestationReport(BaseModel):
    report_id: str = Field(..., description="报告ID")
    enclave_id: str = Field(..., description="Enclave ID")
    timestamp: str = Field(..., description="时间戳")
    measurement: str = Field(..., description="测量值")
    nonce: str = Field(..., description="随机数")
    signature: str = Field(..., description="签名")
    status: str = Field(..., description="验证状态")
    attributes: Dict[str, Any] = Field(default_factory=dict, description="属性")


class SecureData(BaseModel):
    data_id: str = Field(..., description="数据ID")
    enclave_id: str = Field(..., description="Enclave ID")
    encrypted_data: str = Field(..., description="加密数据(base64)")
    sealed: bool = Field(default=False, description="是否密封")
    created_at: str = Field(..., description="创建时间")


class TeeEnclaveManager:
    def __init__(self):
        self.enclaves: Dict[str, EnclaveInfo] = {}
        self.attestation_reports: Dict[str, List[AttestationReport]] = {}
        self.secure_storage: Dict[str, SecureData] = {}
        self._enclave_keys: Dict[str, bytes] = {}

    def create_enclave(
        self,
        name: str,
        enclave_type: str = "simulated",
        memory_size: int = 128,
        attributes: Optional[Dict[str, Any]] = None
    ) -> EnclaveInfo:
        if len(self.enclaves) >= settings.tee_max_enclaves:
            raise RuntimeError(f"Maximum enclaves limit ({settings.tee_max_enclaves}) reached")

        enclave_id = generate_id("enc_")
        now = datetime.utcnow().isoformat()

        measurement = self._generate_measurement(enclave_id, name, enclave_type)

        enclave = EnclaveInfo(
            enclave_id=enclave_id,
            name=name,
            status="created",
            type=enclave_type,
            memory_size=memory_size,
            created_at=now,
            measurement=measurement,
            attributes=attributes or {}
        )

        self.enclaves[enclave_id] = enclave
        self._enclave_keys[enclave_id] = generate_random_bytes(32)
        self.attestation_reports[enclave_id] = []

        return enclave

    def _generate_measurement(self, enclave_id: str, name: str, enclave_type: str) -> str:
        data = {
            "enclave_id": enclave_id,
            "name": name,
            "type": enclave_type,
            "timestamp": datetime.utcnow().isoformat(),
            "random": secrets.token_hex(16)
        }
        return hash_data(data, "sha256")

    def start_enclave(self, enclave_id: str) -> bool:
        if enclave_id not in self.enclaves:
            return False

        enclave = self.enclaves[enclave_id]
        if enclave.status == "destroyed":
            return False

        enclave.status = "running"
        enclave.started_at = datetime.utcnow().isoformat()
        return True

    def pause_enclave(self, enclave_id: str) -> bool:
        if enclave_id not in self.enclaves:
            return False

        enclave = self.enclaves[enclave_id]
        if enclave.status != "running":
            return False

        enclave.status = "paused"
        return True

    def resume_enclave(self, enclave_id: str) -> bool:
        if enclave_id not in self.enclaves:
            return False

        enclave = self.enclaves[enclave_id]
        if enclave.status != "paused":
            return False

        enclave.status = "running"
        return True

    def destroy_enclave(self, enclave_id: str) -> bool:
        if enclave_id not in self.enclaves:
            return False

        enclave = self.enclaves[enclave_id]
        enclave.status = "destroyed"
        enclave.destroyed_at = datetime.utcnow().isoformat()

        if enclave_id in self._enclave_keys:
            del self._enclave_keys[enclave_id]

        return True

    def get_enclave(self, enclave_id: str) -> Optional[EnclaveInfo]:
        return self.enclaves.get(enclave_id)

    def list_enclaves(self, status: Optional[str] = None) -> List[EnclaveInfo]:
        enclaves = list(self.enclaves.values())
        if status:
            enclaves = [e for e in enclaves if e.status == status]
        return enclaves

    def generate_attestation(self, enclave_id: str, nonce: Optional[str] = None) -> Optional[AttestationReport]:
        if enclave_id not in self.enclaves:
            return None

        enclave = self.enclaves[enclave_id]
        if enclave.status not in ["running", "paused"]:
            return None

        nonce = nonce or secrets.token_hex(32)
        now = datetime.utcnow().isoformat()

        report_data = {
            "enclave_id": enclave_id,
            "measurement": enclave.measurement,
            "nonce": nonce,
            "timestamp": now
        }

        signature = self._sign_with_enclave_key(enclave_id, report_data)

        report = AttestationReport(
            report_id=generate_id("rep_"),
            enclave_id=enclave_id,
            timestamp=now,
            measurement=enclave.measurement or "",
            nonce=nonce,
            signature=signature,
            status="valid"
        )

        self.attestation_reports[enclave_id].append(report)
        return report

    def _sign_with_enclave_key(self, enclave_id: str, data: Dict[str, Any]) -> str:
        if enclave_id not in self._enclave_keys:
            raise ValueError("Enclave key not found")

        key = self._enclave_keys[enclave_id]
        data_str = json.dumps(data, sort_keys=True)
        combined = data_str.encode() + key
        return hash_data(combined, "sha256")

    def verify_attestation(self, report: AttestationReport, expected_nonce: Optional[str] = None) -> bool:
        if expected_nonce and report.nonce != expected_nonce:
            return False

        if report.enclave_id not in self.enclaves:
            return False

        enclave = self.enclaves[report.enclave_id]
        if enclave.measurement != report.measurement:
            return False

        report_data = {
            "enclave_id": report.enclave_id,
            "measurement": report.measurement,
            "nonce": report.nonce,
            "timestamp": report.timestamp
        }

        expected_signature = self._sign_with_enclave_key(report.enclave_id, report_data)
        return report.signature == expected_signature

    def encrypt_in_enclave(self, enclave_id: str, plaintext: bytes) -> Optional[SecureData]:
        if enclave_id not in self.enclaves:
            return None

        enclave = self.enclaves[enclave_id]
        if enclave.status != "running":
            return None

        if enclave_id not in self._enclave_keys:
            return None

        key = self._enclave_keys[enclave_id]
        ciphertext = bytes([p ^ k for p, k in zip(plaintext, key * (len(plaintext) // len(key) + 1))])
        encrypted_b64 = base64.b64encode(ciphertext).decode()

        secure_data = SecureData(
            data_id=generate_id("data_"),
            enclave_id=enclave_id,
            encrypted_data=encrypted_b64,
            sealed=True,
            created_at=datetime.utcnow().isoformat()
        )

        self.secure_storage[secure_data.data_id] = secure_data
        return secure_data

    def decrypt_in_enclave(self, data_id: str) -> Optional[bytes]:
        if data_id not in self.secure_storage:
            return None

        secure_data = self.secure_storage[data_id]
        enclave_id = secure_data.enclave_id

        if enclave_id not in self.enclaves:
            return None

        enclave = self.enclaves[enclave_id]
        if enclave.status != "running":
            return None

        if enclave_id not in self._enclave_keys:
            return None

        key = self._enclave_keys[enclave_id]
        ciphertext = base64.b64decode(secure_data.encrypted_data)
        plaintext = bytes([c ^ k for c, k in zip(ciphertext, key * (len(ciphertext) // len(key) + 1))])

        return plaintext

    def execute_secure_function(self, enclave_id: str, function_name: str, *args, **kwargs) -> Dict[str, Any]:
        if enclave_id not in self.enclaves:
            return {"success": False, "error": "Enclave not found"}

        enclave = self.enclaves[enclave_id]
        if enclave.status != "running":
            return {"success": False, "error": "Enclave not running"}

        from .utils import hash_data
        result_hash = hash_data({"function": function_name, "args": args, "kwargs": kwargs}, "sha256")

        return {
            "success": True,
            "enclave_id": enclave_id,
            "function": function_name,
            "result_hash": result_hash,
            "timestamp": datetime.utcnow().isoformat()
        }

    def get_enclave_health(self, enclave_id: str) -> Optional[Dict[str, Any]]:
        if enclave_id not in self.enclaves:
            return None

        enclave = self.enclaves[enclave_id]
        uptime = None
        if enclave.started_at and enclave.status == "running":
            start = datetime.fromisoformat(enclave.started_at)
            uptime = (datetime.utcnow() - start).total_seconds()

        return {
            "enclave_id": enclave_id,
            "status": enclave.status,
            "type": enclave.type,
            "uptime_seconds": uptime,
            "memory_mb": enclave.memory_size,
            "has_key": enclave_id in self._enclave_keys,
            "attestation_count": len(self.attestation_reports.get(enclave_id, []))
        }

    def remote_attestation_challenge(self, enclave_id: str) -> Optional[Dict[str, Any]]:
        nonce = secrets.token_hex(64)
        report = self.generate_attestation(enclave_id, nonce)
        if not report:
            return None

        return {
            "challenge": nonce,
            "report": report.dict(),
            "verification": self.verify_attestation(report, nonce)
        }


_tee_manager_instance: Optional[TeeEnclaveManager] = None


def get_tee_manager() -> TeeEnclaveManager:
    global _tee_manager_instance
    if _tee_manager_instance is None:
        _tee_manager_instance = TeeEnclaveManager()
    return _tee_manager_instance
