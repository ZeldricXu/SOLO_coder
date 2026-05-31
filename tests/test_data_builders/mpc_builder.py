"""MPC模块测试数据构建器"""

from dataclasses import dataclass, field
from datetime import datetime, timezone, timedelta
from enum import Enum
from typing import Any, Dict, List, Optional
from uuid import uuid4
import hashlib
import os


class MpcProtocol(str, Enum):
    Shamir = "Shamir"
    GarbledCircuit = "GarbledCircuit"
    ObliviousTransfer = "ObliviousTransfer"
    SPDZ = "SPDZ"
    ABY3 = "ABY3"


class MpcSessionStatus(str, Enum):
    Created = "Created"
    WaitingForParticipants = "WaitingForParticipants"
    InputsCollected = "InputsCollected"
    Computing = "Computing"
    Completed = "Completed"
    Failed = "Failed"
    Timeout = "Timeout"


class MpcOperation(str, Enum):
    Add = "Add"
    Multiply = "Multiply"
    Compare = "Compare"
    Custom = "Custom"


@dataclass
class MpcParticipant:
    id: str
    index: int
    public_key: bytes
    is_ready: bool = False
    joined_at: Optional[datetime] = None


@dataclass
class EncryptedInput:
    participant_id: str
    encrypted_value: bytes
    commitment: str
    nonce: bytes


@dataclass
class MpcSession:
    id: str
    protocol: MpcProtocol
    operation: MpcOperation
    status: MpcSessionStatus
    min_participants: int
    max_participants: int
    participants: Dict[str, MpcParticipant]
    encrypted_inputs: Dict[str, EncryptedInput]
    result: Optional[bytes] = None
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None
    timeout_at: Optional[datetime] = None
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class MpcSessionCreateRequest:
    protocol: MpcProtocol
    operation: MpcOperation
    min_participants: Optional[int] = None
    max_participants: Optional[int] = None
    timeout_secs: Optional[int] = None
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class MpcJoinRequest:
    session_id: str
    participant_id: str
    public_key: bytes


@dataclass
class MpcSubmitInputRequest:
    session_id: str
    participant_id: str
    encrypted_value: bytes
    commitment: str
    nonce: bytes


@dataclass
class MpcConfig:
    enabled: bool = True
    min_participants: int = 2
    max_participants: int = 10
    protocol_timeout_secs: int = 300


class MpcTestDataBuilder:
    """MPC模块测试数据构建器
    
    用于构建安全多方计算协议执行协调、参与方输入加密与结果解密相关的测试数据。
    """
    
    def __init__(self):
        self._session_counter = 0
        self._participant_counter = 0
    
    def _generate_id(self, prefix: str) -> str:
        """生成唯一ID"""
        return f"{prefix}_{uuid4().hex[:12]}"
    
    def _generate_key_pair(self) -> bytes:
        """生成模拟公钥"""
        return os.urandom(32)
    
    def _generate_nonce(self) -> bytes:
        """生成Nonce"""
        return os.urandom(12)
    
    def _encrypt_input(self, plaintext: bytes, key: Optional[bytes] = None) -> tuple[bytes, bytes, str]:
        """模拟加密输入
        
        返回: (encrypted_value, key, commitment)
        """
        if key is None:
            key = os.urandom(32)
        
        encrypted = bytearray()
        for i, p in enumerate(plaintext):
            encrypted.append(p ^ key[i % len(key)])
        encrypted = bytes(encrypted)
        
        commitment = hashlib.sha256(encrypted + self._generate_nonce()).hexdigest()
        
        return encrypted, key, commitment
    
    def build_session_create_request(
        self,
        protocol: MpcProtocol = MpcProtocol.Shamir,
        operation: MpcOperation = MpcOperation.Add,
        min_participants: Optional[int] = None,
        max_participants: Optional[int] = None,
        timeout_secs: Optional[int] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> MpcSessionCreateRequest:
        """构建会话创建请求
        
        Args:
            protocol: MPC协议类型
            operation: 计算操作类型
            min_participants: 最小参与方数量
            max_participants: 最大参与方数量
            timeout_secs: 超时时间（秒）
            metadata: 元数据
        """
        return MpcSessionCreateRequest(
            protocol=protocol,
            operation=operation,
            min_participants=min_participants,
            max_participants=max_participants,
            timeout_secs=timeout_secs,
            metadata=metadata or {"task": "secure_computation"},
        )
    
    def build_join_request(
        self,
        session_id: str,
        participant_id: Optional[str] = None,
    ) -> MpcJoinRequest:
        """构建加入会话请求
        
        Args:
            session_id: 会话ID
            participant_id: 参与方ID
        """
        return MpcJoinRequest(
            session_id=session_id,
            participant_id=participant_id or self._generate_id("part"),
            public_key=self._generate_key_pair(),
        )
    
    def build_submit_input_request(
        self,
        session_id: str,
        participant_id: str,
        plaintext: Optional[bytes] = None,
    ) -> MpcSubmitInputRequest:
        """构建提交加密输入请求
        
        Args:
            session_id: 会话ID
            participant_id: 参与方ID
            plaintext: 明文输入（用于模拟加密）
        """
        if plaintext is None:
            plaintext = os.urandom(8)
        
        nonce = self._generate_nonce()
        encrypted, _, commitment = self._encrypt_input(plaintext)
        
        return MpcSubmitInputRequest(
            session_id=session_id,
            participant_id=participant_id,
            encrypted_value=encrypted,
            commitment=commitment,
            nonce=nonce,
        )
    
    def build_participant(
        self,
        participant_id: Optional[str] = None,
        index: int = 0,
        is_ready: bool = False,
    ) -> MpcParticipant:
        """构建参与方对象
        
        Args:
            participant_id: 参与方ID
            index: 参与方索引
            is_ready: 是否已准备好
        """
        self._participant_counter += 1
        return MpcParticipant(
            id=participant_id or self._generate_id("part"),
            index=index,
            public_key=self._generate_key_pair(),
            is_ready=is_ready,
            joined_at=datetime.now(timezone.utc),
        )
    
    def build_encrypted_input(
        self,
        participant_id: str,
        plaintext: Optional[bytes] = None,
    ) -> EncryptedInput:
        """构建加密输入对象
        
        Args:
            participant_id: 参与方ID
            plaintext: 明文输入
        """
        if plaintext is None:
            plaintext = os.urandom(8)
        
        nonce = self._generate_nonce()
        encrypted, _, commitment = self._encrypt_input(plaintext)
        
        return EncryptedInput(
            participant_id=participant_id,
            encrypted_value=encrypted,
            commitment=commitment,
            nonce=nonce,
        )
    
    def build_session(
        self,
        protocol: MpcProtocol = MpcProtocol.Shamir,
        operation: MpcOperation = MpcOperation.Add,
        min_participants: int = 2,
        max_participants: int = 10,
        status: MpcSessionStatus = MpcSessionStatus.Created,
        num_participants: int = 0,
        num_ready: int = 0,
        timeout_secs: int = 300,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> MpcSession:
        """构建MPC会话对象
        
        Args:
            protocol: MPC协议类型
            operation: 计算操作类型
            min_participants: 最小参与方数量
            max_participants: 最大参与方数量
            status: 会话状态
            num_participants: 已加入的参与方数量
            num_ready: 已提交输入的参与方数量
            timeout_secs: 超时时间
            metadata: 元数据
        """
        self._session_counter += 1
        session_id = self._generate_id("mpc")
        now = datetime.now(timezone.utc)
        
        participants: Dict[str, MpcParticipant] = {}
        encrypted_inputs: Dict[str, EncryptedInput] = {}
        
        for i in range(num_participants):
            participant = self.build_participant(index=i, is_ready=i < num_ready)
            participants[participant.id] = participant
            
            if i < num_ready:
                enc_input = self.build_encrypted_input(participant.id)
                encrypted_inputs[participant.id] = enc_input
        
        return MpcSession(
            id=session_id,
            protocol=protocol,
            operation=operation,
            status=status,
            min_participants=min_participants,
            max_participants=max_participants,
            participants=participants,
            encrypted_inputs=encrypted_inputs,
            created_at=now,
            updated_at=now,
            timeout_at=now + timedelta(seconds=timeout_secs),
            metadata=metadata or {"task": "test_computation"},
        )
    
    def build_invalid_session_create_requests(self) -> List[MpcSessionCreateRequest]:
        """构建无效的会话创建请求（边界条件测试）"""
        return [
            MpcSessionCreateRequest(
                protocol=MpcProtocol.Shamir,
                operation=MpcOperation.Add,
                min_participants=1,
                max_participants=10,
            ),
            MpcSessionCreateRequest(
                protocol=MpcProtocol.Shamir,
                operation=MpcOperation.Add,
                min_participants=5,
                max_participants=3,
            ),
            MpcSessionCreateRequest(
                protocol=MpcProtocol.Shamir,
                operation=MpcOperation.Add,
                min_participants=2,
                max_participants=100,
            ),
        ]
    
    def build_participant_lists(
        self,
        session_id: str,
        count: int,
    ) -> List[MpcJoinRequest]:
        """构建参与方加入请求列表"""
        return [
            self.build_join_request(session_id)
            for _ in range(count)
        ]
    
    def build_test_inputs(
        self,
        session_id: str,
        participant_ids: List[str],
        values: Optional[List[int]] = None,
    ) -> List[MpcSubmitInputRequest]:
        """构建测试输入提交请求列表
        
        Args:
            session_id: 会话ID
            participant_ids: 参与方ID列表
            values: 测试值列表（整数会被转换为bytes）
        """
        if values is None:
            values = [i + 1 for i in range(len(participant_ids))]
        
        requests = []
        for pid, val in zip(participant_ids, values):
            plaintext = val.to_bytes(8, 'big')
            req = self.build_submit_input_request(session_id, pid, plaintext)
            requests.append(req)
        
        return requests
    
    def build_edge_case_inputs(self) -> List[bytes]:
        """构建边界情况的输入数据"""
        return [
            b"",
            b"\x00" * 32,
            b"\xff" * 32,
            os.urandom(1),
            os.urandom(64),
            os.urandom(1024),
        ]
    
    def build_custom_operation_metadata(self, op_type: str = "xor") -> Dict[str, Any]:
        """构建自定义操作的元数据"""
        return {
            "custom_op": op_type,
            "description": f"Custom {op_type} operation",
        }
    
    def build_timeout_session(
        self,
        timeout_ago_secs: int = 60,
    ) -> MpcSession:
        """构建已超时的会话"""
        session = self.build_session()
        session.timeout_at = datetime.now(timezone.utc) - timedelta(seconds=timeout_ago_secs)
        session.status = MpcSessionStatus.Timeout
        return session
    
    def build_failed_session(
        self,
        error_message: str = "Computation failed",
    ) -> MpcSession:
        """构建失败的会话"""
        session = self.build_session(status=MpcSessionStatus.Failed)
        session.metadata["error"] = error_message
        return session
    
    def build_completed_session(
        self,
        result: Optional[bytes] = None,
    ) -> MpcSession:
        """构建已完成的会话"""
        session = self.build_session(
            status=MpcSessionStatus.Completed,
            num_participants=3,
            num_ready=3,
        )
        session.result = result or os.urandom(32)
        return session
    
    def build_config(self, **overrides) -> MpcConfig:
        """构建MPC配置
        
        Args:
            **overrides: 覆盖默认配置的参数
        """
        config = MpcConfig()
        for key, value in overrides.items():
            if hasattr(config, key):
                setattr(config, key, value)
        return config
