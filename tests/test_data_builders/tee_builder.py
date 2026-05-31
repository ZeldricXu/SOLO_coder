"""TEE模块测试数据构建器"""

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Dict, List, Optional
from uuid import uuid4
import hashlib
import hmac
import json
import time


class EnclaveStatus(str, Enum):
    Created = "Created"
    Initializing = "Initializing"
    Running = "Running"
    Attested = "Attested"
    Paused = "Paused"
    Stopped = "Stopped"
    Failed = "Failed"


class TeeTechnology(str, Enum):
    SGX = "SGX"
    SEV = "SEV"
    TrustZone = "TrustZone"
    Generic = "Generic"


@dataclass
class EnclaveCreateRequest:
    technology: TeeTechnology
    metadata: Dict[str, Any]
    signature: str
    timestamp: int


@dataclass
class AttestationRequest:
    enclave_id: str
    challenge: str
    signature: str
    timestamp: int


@dataclass
class EnclaveExecuteRequest:
    enclave_id: str
    command: str
    arguments: Dict[str, Any]
    signature: str
    timestamp: int


@dataclass
class Enclave:
    id: str
    technology: TeeTechnology
    status: EnclaveStatus
    created_at: datetime
    updated_at: datetime
    metadata: Dict[str, Any]
    measurement: Optional[str] = None
    attestation_token: Optional[str] = None


@dataclass
class AttestationResponse:
    enclave_id: str
    measurement: str
    quote: str
    signature: str
    timestamp: int


@dataclass
class TeeConfig:
    enabled: bool = True
    max_enclaves: int = 64
    attestation_timeout_ms: int = 30000
    supported_techs: List[str] = field(
        default_factory=lambda: ["SGX", "SEV", "TrustZone"]
    )


class TeeTestDataBuilder:
    """TEE模块测试数据构建器
    
    用于构建TEE enclave管理、安全认证与远程证明相关的测试数据。
    """
    
    DEFAULT_SECRET_KEY = b"test-secret-key-for-signing-12345"
    
    def __init__(self, secret_key: Optional[bytes] = None):
        self.secret_key = secret_key or self.DEFAULT_SECRET_KEY
        self._enclave_counter = 0
    
    def _generate_id(self, prefix: str) -> str:
        """生成唯一ID"""
        return f"{prefix}_{uuid4().hex[:12]}"
    
    def _sign_data(self, data: bytes, timestamp: int) -> str:
        """使用HMAC-SHA256签名数据"""
        data_to_sign = data + timestamp.to_bytes(8, 'little')
        return hmac.new(self.secret_key, data_to_sign, hashlib.sha256).hexdigest()
    
    def _current_timestamp(self) -> int:
        """获取当前时间戳"""
        return int(time.time())
    
    def _past_timestamp(self, seconds_ago: int) -> int:
        """获取过去的时间戳"""
        return int(time.time()) - seconds_ago
    
    def _future_timestamp(self, seconds_ahead: int) -> int:
        """获取未来的时间戳"""
        return int(time.time()) + seconds_ahead
    
    def build_enclave_create_request(
        self,
        technology: TeeTechnology = TeeTechnology.SGX,
        metadata: Optional[Dict[str, Any]] = None,
        timestamp: Optional[int] = None,
        valid_signature: bool = True,
    ) -> EnclaveCreateRequest:
        """构建Enclave创建请求
        
        Args:
            technology: TEE技术类型
            metadata: 元数据
            timestamp: 时间戳，默认使用当前时间
            valid_signature: 是否生成有效签名
        """
        ts = self._current_timestamp() if timestamp is None else timestamp
        meta = {"image": "secure-enclave:v1.0", "memory_mb": 1024} if metadata is None else metadata
        
        payload_bytes = json.dumps({
            "technology": technology.value,
            "metadata": meta,
        }, sort_keys=True).encode()
        
        if valid_signature:
            signature = self._sign_data(payload_bytes, ts)
        else:
            signature = "invalid_signature_" + uuid4().hex
        
        return EnclaveCreateRequest(
            technology=technology,
            metadata=meta,
            signature=signature,
            timestamp=ts,
        )
    
    def build_attestation_request(
        self,
        enclave_id: str,
        challenge: Optional[str] = None,
        timestamp: Optional[int] = None,
        valid_signature: bool = True,
        expired: bool = False,
    ) -> AttestationRequest:
        """构建远程证明请求
        
        Args:
            enclave_id: Enclave ID
            challenge: 挑战值
            timestamp: 时间戳
            valid_signature: 是否生成有效签名
            expired: 是否过期
        """
        if expired:
            ts = self._past_timestamp(600)
        else:
            ts = timestamp or self._current_timestamp()
        
        chal = challenge or f"challenge_{uuid4().hex}"
        
        payload_bytes = json.dumps({
            "enclave_id": enclave_id,
            "challenge": chal,
        }, sort_keys=True).encode()
        
        if valid_signature:
            signature = self._sign_data(payload_bytes, ts)
        else:
            signature = "invalid_attestation_sig"
        
        return AttestationRequest(
            enclave_id=enclave_id,
            challenge=chal,
            signature=signature,
            timestamp=ts,
        )
    
    def build_execute_request(
        self,
        enclave_id: str,
        command: str = "get_status",
        arguments: Optional[Dict[str, Any]] = None,
        timestamp: Optional[int] = None,
        valid_signature: bool = True,
    ) -> EnclaveExecuteRequest:
        """构建执行请求
        
        Args:
            enclave_id: Enclave ID
            command: 执行命令
            arguments: 命令参数
            timestamp: 时间戳
            valid_signature: 是否生成有效签名
        """
        ts = timestamp or self._current_timestamp()
        args = arguments or {"param": "value"}
        
        payload_bytes = json.dumps({
            "enclave_id": enclave_id,
            "command": command,
            "arguments": args,
        }, sort_keys=True).encode()
        
        if valid_signature:
            signature = self._sign_data(payload_bytes, ts)
        else:
            signature = "invalid_execute_sig"
        
        return EnclaveExecuteRequest(
            enclave_id=enclave_id,
            command=command,
            arguments=args,
            signature=signature,
            timestamp=ts,
        )
    
    def build_enclave(
        self,
        technology: TeeTechnology = TeeTechnology.SGX,
        status: EnclaveStatus = EnclaveStatus.Created,
        metadata: Optional[Dict[str, Any]] = None,
        with_measurement: bool = True,
        with_attestation_token: bool = False,
    ) -> Enclave:
        """构建Enclave对象
        
        Args:
            technology: TEE技术类型
            status: 初始状态
            metadata: 元数据
            with_measurement: 是否包含度量值
            with_attestation_token: 是否包含证明令牌
        """
        self._enclave_counter += 1
        enclave_id = self._generate_id("enc")
        now = datetime.now(timezone.utc)
        
        measurement = None
        if with_measurement:
            measurement = hashlib.sha256(
                f"{enclave_id}:{technology.value}:{now.timestamp()}".encode()
            ).hexdigest()
        
        attestation_token = None
        if with_attestation_token:
            attestation_token = hashlib.sha256(
                f"{enclave_id}:{now.timestamp()}:attested".encode()
            ).hexdigest()
        
        return Enclave(
            id=enclave_id,
            technology=technology,
            status=status,
            created_at=now,
            updated_at=now,
            metadata=metadata or {"image": "test-image:v1"},
            measurement=measurement,
            attestation_token=attestation_token,
        )
    
    def build_valid_enclave_ids(self, count: int = 5) -> List[str]:
        """构建有效的Enclave ID列表"""
        return [self._generate_id("enc") for _ in range(count)]
    
    def build_invalid_enclave_ids(self) -> List[str]:
        """构建无效的Enclave ID列表（边界条件测试）"""
        return [
            "",
            "nonexistent_enclave_id",
            "enc_000000000000000000000000000000000000",
            "!@#$%^&*()",
            " " * 100,
        ]
    
    def build_unsupported_technologies(self) -> List[TeeTechnology]:
        """构建不支持的TEE技术列表"""
        return [TeeTechnology.Generic]
    
    def build_expired_timestamp(self) -> int:
        """构建过期的时间戳（超过300秒）"""
        return self._past_timestamp(301)
    
    def build_future_timestamp(self) -> int:
        """构建未来的时间戳"""
        return self._future_timestamp(60)
    
    def build_edge_case_metadata(self) -> List[Dict[str, Any]]:
        """构建边界情况的元数据"""
        return [
            {},
            {"key": "value"},
            {"nested": {"deep": {"value": 123}}},
            {"large": "x" * 10000},
            {"special": "!@#$%^&*()_+-=[]{}|;':\",./<>?"},
            {"unicode": "中文测试 🎉"},
            {"null_value": None},
            {"array": [1, 2, 3]},
        ]
    
    def build_config(self, **overrides) -> TeeConfig:
        """构建TEE配置
        
        Args:
            **overrides: 覆盖默认配置的参数
        """
        config = TeeConfig()
        for key, value in overrides.items():
            if hasattr(config, key):
                setattr(config, key, value)
        return config
