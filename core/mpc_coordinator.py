import secrets
from datetime import datetime
from typing import Any, Dict, List, Optional, Tuple
from pydantic import BaseModel, Field

from .utils import generate_id


class MPCParty(BaseModel):
    party_id: str = Field(..., description="参与方ID")
    name: str = Field(..., description="参与方名称")
    address: str = Field(..., description="参与方地址")
    public_key: Optional[str] = Field(None, description="公钥")
    status: str = Field(default="idle", description="状态: idle, computing, completed, failed")
    input_received: bool = Field(default=False, description="是否已接收输入")
    last_seen: Optional[str] = Field(None, description="最后在线时间")


class MPCSession(BaseModel):
    session_id: str = Field(..., description="会话ID")
    name: str = Field(..., description="会话名称")
    protocol: str = Field(..., description="MPC协议类型")
    status: str = Field(default="created", description="状态: created, waiting_inputs, computing, completed, failed")
    parties: List[str] = Field(default_factory=list, description="参与方ID列表")
    threshold: int = Field(default=2, description="阈值")
    operation: str = Field(..., description="计算操作: sum, average, product, max, min, custom")
    created_at: str = Field(..., description="创建时间")
    started_at: Optional[str] = Field(None, description="开始时间")
    completed_at: Optional[str] = Field(None, description="完成时间")
    result: Optional[Any] = Field(None, description="计算结果")
    error_detail: Optional[str] = Field(None, description="错误详情")


class EncryptedInput(BaseModel):
    input_id: str = Field(..., description="输入ID")
    session_id: str = Field(..., description="会话ID")
    party_id: str = Field(..., description="参与方ID")
    encrypted_value: str = Field(..., description="加密值")
    commitments: List[str] = Field(default_factory=list, description="承诺列表")
    received_at: str = Field(..., description="接收时间")


class MPCProtocol(BaseModel):
    protocol_id: str = Field(..., description="协议ID")
    name: str = Field(..., description="协议名称")
    description: str = Field(..., description="描述")
    supported_operations: List[str] = Field(default_factory=list, description="支持的操作")
    min_parties: int = Field(default=2, description="最少参与方数")
    max_parties: int = Field(default=100, description="最多参与方数")


class MPCCoordinator:
    def __init__(self):
        self.parties: Dict[str, MPCParty] = {}
        self.sessions: Dict[str, MPCSession] = {}
        self.inputs: Dict[str, List[EncryptedInput]] = {}
        self.protocols: Dict[str, MPCProtocol] = {}
        self._session_secrets: Dict[str, List[int]] = {}
        self._init_default_protocols()

    def _init_default_protocols(self):
        default_protocols = [
            MPCProtocol(
                protocol_id="prot_001",
                name="Shamir Secret Sharing",
                description="基于Shamir秘密共享的安全计算协议",
                supported_operations=["sum", "average", "product"],
                min_parties=2,
                max_parties=100
            ),
            MPCProtocol(
                protocol_id="prot_002",
                name="Garbled Circuits",
                description="基于混淆电路的安全计算协议",
                supported_operations=["sum", "average", "max", "min", "custom"],
                min_parties=2,
                max_parties=10
            ),
            MPCProtocol(
                protocol_id="prot_003",
                name="Homomorphic Encryption",
                description="基于同态加密的安全计算协议",
                supported_operations=["sum", "average", "product"],
                min_parties=2,
                max_parties=50
            )
        ]
        for protocol in default_protocols:
            self.protocols[protocol.protocol_id] = protocol

    def register_party(
        self,
        name: str,
        address: str,
        public_key: Optional[str] = None
    ) -> MPCParty:
        party_id = generate_id("pty_")
        party = MPCParty(
            party_id=party_id,
            name=name,
            address=address,
            public_key=public_key,
            last_seen=datetime.utcnow().isoformat()
        )
        self.parties[party_id] = party
        return party

    def unregister_party(self, party_id: str) -> bool:
        if party_id in self.parties:
            del self.parties[party_id]
            return True
        return False

    def get_party(self, party_id: str) -> Optional[MPCParty]:
        return self.parties.get(party_id)

    def list_parties(self, status: Optional[str] = None) -> List[MPCParty]:
        parties = list(self.parties.values())
        if status:
            parties = [p for p in parties if p.status == status]
        return parties

    def create_session(
        self,
        name: str,
        protocol: str,
        operation: str,
        party_ids: List[str],
        threshold: Optional[int] = None
    ) -> Optional[MPCSession]:
        if protocol not in self.protocols:
            return None

        protocol_def = self.protocols[protocol]
        if len(party_ids) < protocol_def.min_parties or len(party_ids) > protocol_def.max_parties:
            return None

        if operation not in protocol_def.supported_operations:
            return None

        session_id = generate_id("ses_")
        now = datetime.utcnow().isoformat()

        session = MPCSession(
            session_id=session_id,
            name=name,
            protocol=protocol,
            status="created",
            parties=party_ids,
            threshold=threshold or max(2, len(party_ids) // 2 + 1),
            operation=operation,
            created_at=now
        )

        self.sessions[session_id] = session
        self.inputs[session_id] = []
        self._session_secrets[session_id] = []

        return session

    def start_session(self, session_id: str) -> bool:
        if session_id not in self.sessions:
            return False

        session = self.sessions[session_id]
        if session.status != "created":
            return False

        session.status = "waiting_inputs"
        session.started_at = datetime.utcnow().isoformat()

        for party_id in session.parties:
            if party_id in self.parties:
                self.parties[party_id].status = "computing"

        return True

    def submit_encrypted_input(
        self,
        session_id: str,
        party_id: str,
        encrypted_value: str,
        commitments: Optional[List[str]] = None
    ) -> Optional[EncryptedInput]:
        if session_id not in self.sessions:
            return None

        session = self.sessions[session_id]
        if session.status != "waiting_inputs":
            return None

        if party_id not in session.parties:
            return None

        input_id = generate_id("inp_")
        encrypted_input = EncryptedInput(
            input_id=input_id,
            session_id=session_id,
            party_id=party_id,
            encrypted_value=encrypted_value,
            commitments=commitments or [],
            received_at=datetime.utcnow().isoformat()
        )

        self.inputs[session_id].append(encrypted_input)

        if party_id in self.parties:
            self.parties[party_id].input_received = True

        if self._all_inputs_received(session_id):
            session.status = "computing"

        return encrypted_input

    def _all_inputs_received(self, session_id: str) -> bool:
        if session_id not in self.sessions:
            return False

        session = self.sessions[session_id]
        received = [inp.party_id for inp in self.inputs.get(session_id, [])]
        return all(pid in received for pid in session.parties)

    def _generate_shares(self, secret: int, num_parties: int, threshold: int) -> List[int]:
        coefficients = [secret]
        for _ in range(threshold - 1):
            coefficients.append(secrets.randbelow(2**32))

        shares = []
        for i in range(1, num_parties + 1):
            result = 0
            for coeff in reversed(coefficients):
                result = result * i + coeff
            shares.append(result)

        return shares

    def _reconstruct_secret(self, shares: List[Tuple[int, int]]) -> int:
        result = 0
        for i, (xi, yi) in enumerate(shares):
            numerator = 1
            denominator = 1
            for j, (xj, _) in enumerate(shares):
                if i != j:
                    numerator *= -xj
                    denominator *= (xi - xj)
            result += yi * numerator // denominator
        return result

    def execute_computation(self, session_id: str) -> Optional[Dict[str, Any]]:
        if session_id not in self.sessions:
            return None

        session = self.sessions[session_id]
        if session.status != "computing":
            return None

        inputs = self.inputs.get(session_id, [])
        if len(inputs) < session.threshold:
            return None

        try:
            decrypted_values = []
            for inp in inputs:
                try:
                    value = int(inp.encrypted_value)
                    decrypted_values.append(value)
                except (ValueError, TypeError):
                    decrypted_values.append(0)

            result = self._compute_operation(session.operation, decrypted_values)

            session.result = result
            session.status = "completed"
            session.completed_at = datetime.utcnow().isoformat()

            for party_id in session.parties:
                if party_id in self.parties:
                    self.parties[party_id].status = "completed"
                    self.parties[party_id].input_received = False

            return {
                "session_id": session_id,
                "operation": session.operation,
                "result": result,
                "num_parties": len(session.parties),
                "inputs_count": len(inputs)
            }
        except Exception as e:
            session.status = "failed"
            session.error_detail = str(e)
            return None

    def _compute_operation(self, operation: str, values: List[float]) -> Any:
        if not values:
            return None

        if operation == "sum":
            return sum(values)
        elif operation == "average":
            return sum(values) / len(values)
        elif operation == "product":
            result = 1
            for v in values:
                result *= v
            return result
        elif operation == "max":
            return max(values)
        elif operation == "min":
            return min(values)
        else:
            return sum(values)

    def get_session(self, session_id: str) -> Optional[MPCSession]:
        return self.sessions.get(session_id)

    def list_sessions(self, status: Optional[str] = None) -> List[MPCSession]:
        sessions = list(self.sessions.values())
        if status:
            sessions = [s for s in sessions if s.status == status]
        return sessions

    def get_session_inputs(self, session_id: str) -> List[EncryptedInput]:
        return self.inputs.get(session_id, [])

    def get_session_result(self, session_id: str, party_id: Optional[str] = None) -> Optional[Dict[str, Any]]:
        if session_id not in self.sessions:
            return None

        session = self.sessions[session_id]
        if session.status != "completed":
            return None

        if party_id and party_id not in session.parties:
            return None

        return {
            "session_id": session_id,
            "operation": session.operation,
            "result": session.result,
            "completed_at": session.completed_at,
            "num_parties": len(session.parties)
        }

    def add_protocol(self, protocol: MPCProtocol) -> None:
        self.protocols[protocol.protocol_id] = protocol

    def get_protocol(self, protocol_id: str) -> Optional[MPCProtocol]:
        return self.protocols.get(protocol_id)

    def list_protocols(self) -> List[MPCProtocol]:
        return list(self.protocols.values())

    def cancel_session(self, session_id: str) -> bool:
        if session_id not in self.sessions:
            return False

        session = self.sessions[session_id]
        if session.status in ["completed", "failed"]:
            return False

        session.status = "failed"
        session.error_detail = "Session cancelled by coordinator"

        for party_id in session.parties:
            if party_id in self.parties:
                self.parties[party_id].status = "idle"
                self.parties[party_id].input_received = False

        return True

    def get_session_progress(self, session_id: str) -> Optional[Dict[str, Any]]:
        if session_id not in self.sessions:
            return None

        session = self.sessions[session_id]
        inputs = self.inputs.get(session_id, [])
        received = len(inputs)
        expected = len(session.parties)

        return {
            "session_id": session_id,
            "status": session.status,
            "inputs_received": received,
            "inputs_expected": expected,
            "progress": received / expected if expected > 0 else 0,
            "operation": session.operation,
            "protocol": session.protocol
        }

    def verify_inputs(self, session_id: str) -> Dict[str, Any]:
        if session_id not in self.sessions:
            return {"valid": False, "error": "Session not found"}

        inputs = self.inputs.get(session_id, [])
        party_ids = set(self.sessions[session_id].parties)
        received_parties = set(inp.party_id for inp in inputs)

        missing = party_ids - received_parties
        extra = received_parties - party_ids

        return {
            "valid": len(missing) == 0 and len(extra) == 0,
            "missing_parties": list(missing),
            "extra_parties": list(extra),
            "total_inputs": len(inputs)
        }

    def get_statistics(self) -> Dict[str, Any]:
        active_sessions = len([s for s in self.sessions.values() if s.status in ["waiting_inputs", "computing"]])
        completed_sessions = len([s for s in self.sessions.values() if s.status == "completed"])
        failed_sessions = len([s for s in self.sessions.values() if s.status == "failed"])

        return {
            "total_parties": len(self.parties),
            "active_parties": len([p for p in self.parties.values() if p.status == "computing"]),
            "total_sessions": len(self.sessions),
            "active_sessions": active_sessions,
            "completed_sessions": completed_sessions,
            "failed_sessions": failed_sessions,
            "supported_protocols": len(self.protocols)
        }


_mpc_coordinator_instance: Optional[MPCCoordinator] = None


def get_mpc_coordinator() -> MPCCoordinator:
    global _mpc_coordinator_instance
    if _mpc_coordinator_instance is None:
        _mpc_coordinator_instance = MPCCoordinator()
    return _mpc_coordinator_instance
