from datetime import datetime
from typing import Any, Dict, List, Optional, Callable, Tuple
from dataclasses import dataclass, field
from enum import Enum
import hashlib
import secrets
import asyncio
import uuid

from app.core.logger import logger
from app.core.events import event_bus, EventType, build_event


class MPCProtocol(str, Enum):
    SECRET_SHARING = "secret_sharing"
    GARBLING = "garbling"
    HOMOMORPHIC = "homomorphic"
    MULTIPARTY_OT = "oblivious_transfer"


class ComputationPhase(str, Enum):
    INIT = "init"
    INPUT_ENCRYPTION = "input_encryption"
    COMPUTATION = "computation"
    RESULT_DECRYPTION = "result_decryption"
    COMPLETED = "completed"
    FAILED = "failed"


class ParticipantStatus(str, Enum):
    WAITING = "waiting"
    READY = "ready"
    COMPUTING = "computing"
    DONE = "done"
    FAILED = "failed"


@dataclass
class Participant:
    participant_id: str
    name: str
    public_key: str
    status: ParticipantStatus = ParticipantStatus.WAITING
    input_commitment: Optional[str] = None
    encrypted_input: Optional[bytes] = None
    computed_share: Optional[Any] = None


@dataclass
class MPCSession:
    session_id: str
    protocol: MPCProtocol
    participants: List[Participant]
    phase: ComputationPhase = ComputationPhase.INIT
    operation: str = "sum"
    inputs: Dict[str, Any] = field(default_factory=dict)
    intermediate_results: Dict[str, Any] = field(default_factory=dict)
    final_result: Optional[Any] = None
    created_at: datetime = field(default_factory=datetime.utcnow)
    completed_at: Optional[datetime] = None
    error_message: Optional[str] = None


@dataclass
class EncryptedInput:
    participant_id: str
    ciphertext: bytes
    nonce: bytes
    commitment: str
    timestamp: datetime


class SecureComputationEngine:
    def __init__(self):
        self._prime = 2**61 - 1
        logger.info("SecureComputationEngine initialized")

    def _generate_random_field_element(self) -> int:
        return secrets.randbelow(self._prime)

    def secret_share(self, secret: int, num_participants: int, threshold: int) -> List[int]:
        if threshold > num_participants:
            raise ValueError("Threshold cannot exceed number of participants")

        coefficients = [secret] + [self._generate_random_field_element() for _ in range(threshold - 1)]

        shares = []
        for i in range(1, num_participants + 1):
            share = 0
            x = i
            power = 1
            for coef in coefficients:
                share = (share + coef * power) % self._prime
                power = (power * x) % self._prime
            shares.append(share)

        return shares

    def secret_reconstruct(self, shares: List[Tuple[int, int]], threshold: int) -> int:
        if len(shares) < threshold:
            raise ValueError(f"Need at least {threshold} shares to reconstruct")

        result = 0
        for i, (x_i, y_i) in enumerate(shares):
            numerator = 1
            denominator = 1
            for j, (x_j, _) in enumerate(shares):
                if i != j:
                    numerator = (numerator * (-x_j)) % self._prime
                    denominator = (denominator * (x_i - x_j)) % self._prime
            lagrange = (numerator * pow(denominator, self._prime - 2, self._prime)) % self._prime
            result = (result + y_i * lagrange) % self._prime

        return result

    def add_encrypted(self, share_a: int, share_b: int) -> int:
        return (share_a + share_b) % self._prime

    def multiply_encrypted(self, share_a: int, share_b: int) -> int:
        return (share_a * share_b) % self._prime

    def scalar_multiply(self, share: int, scalar: int) -> int:
        return (share * scalar) % self._prime

    def sum_shares(self, all_shares: List[List[int]]) -> List[int]:
        if not all_shares:
            return []
        result = all_shares[0].copy()
        for shares in all_shares[1:]:
            for i in range(len(shares)):
                result[i] = self.add_encrypted(result[i], shares[i])
        return result

    def compute_commitment(self, value: Any, nonce: Optional[bytes] = None) -> Tuple[str, bytes]:
        if nonce is None:
            nonce = secrets.token_bytes(16)
        content = f"{value}{nonce.hex()}".encode("utf-8")
        commitment = hashlib.sha256(content).hexdigest()
        return commitment, nonce

    def verify_commitment(self, value: Any, commitment: str, nonce: bytes) -> bool:
        expected, _ = self.compute_commitment(value, nonce)
        return expected == commitment

    def encrypt_input(self, value: Any, public_key: str) -> EncryptedInput:
        value_bytes = str(value).encode("utf-8")
        nonce = secrets.token_bytes(12)

        key = hashlib.sha256(public_key.encode()).digest()
        ciphertext = bytes([b ^ key[i % len(key)] for i, b in enumerate(value_bytes)])

        commitment, _ = self.compute_commitment(value, nonce)

        return EncryptedInput(
            participant_id="",
            ciphertext=ciphertext,
            nonce=nonce,
            commitment=commitment,
            timestamp=datetime.utcnow()
        )

    def decrypt_result(self, encrypted_result: Any, private_key: str) -> Any:
        if isinstance(encrypted_result, bytes):
            key = hashlib.sha256(private_key.encode()).digest()
            decrypted = bytes([b ^ key[i % len(key)] for i, b in enumerate(encrypted_result)])
            try:
                return float(decrypted.decode("utf-8"))
            except ValueError:
                try:
                    return int(decrypted.decode("utf-8"))
                except ValueError:
                    return decrypted.decode("utf-8")
        return encrypted_result


class MPCProtocolCoordinator:
    def __init__(self, engine: SecureComputationEngine):
        self._engine = engine
        self._sessions: Dict[str, MPCSession] = {}
        self._participant_callbacks: Dict[str, Callable] = {}
        logger.info("MPCProtocolCoordinator initialized")

    def create_session(self, protocol: MPCProtocol, operation: str,
                        participant_ids: List[str], threshold: Optional[int] = None) -> MPCSession:
        session_id = f"mpc_{uuid.uuid4().hex[:8]}"

        participants = []
        for pid in participant_ids:
            participant = Participant(
                participant_id=pid,
                name=f"Participant_{pid}",
                public_key=f"pubkey_{pid}"
            )
            participants.append(participant)

        session = MPCSession(
            session_id=session_id,
            protocol=protocol,
            participants=participants,
            operation=operation,
        )

        self._sessions[session_id] = session
        logger.info(f"Created MPC session: {session_id} with {len(participants)} participants")
        return session

    def register_participant_callback(self, participant_id: str, callback: Callable):
        self._participant_callbacks[participant_id] = callback

    def get_session(self, session_id: str) -> Optional[MPCSession]:
        return self._sessions.get(session_id)

    def list_sessions(self) -> List[MPCSession]:
        return list(self._sessions.values())

    async def submit_input(self, session_id: str, participant_id: str,
                           value: Any) -> bool:
        session = self._sessions.get(session_id)
        if not session:
            return False

        participant = next((p for p in session.participants if p.participant_id == participant_id), None)
        if not participant:
            return False

        session.phase = ComputationPhase.INPUT_ENCRYPTION

        encrypted = self._engine.encrypt_input(value, participant.public_key)
        encrypted.participant_id = participant_id

        participant.encrypted_input = encrypted.ciphertext
        participant.input_commitment = encrypted.commitment
        participant.status = ParticipantStatus.READY

        session.inputs[participant_id] = value

        logger.info(f"Participant {participant_id} submitted input to session {session_id}")
        return True

    def all_inputs_ready(self, session_id: str) -> bool:
        session = self._sessions.get(session_id)
        if not session:
            return False
        return all(p.status == ParticipantStatus.READY for p in session.participants)

    async def execute_computation(self, session_id: str) -> Optional[Any]:
        session = self._sessions.get(session_id)
        if not session:
            return None

        try:
            if not self.all_inputs_ready(session_id):
                raise ValueError("Not all participants have submitted inputs")

            session.phase = ComputationPhase.COMPUTATION
            for p in session.participants:
                p.status = ParticipantStatus.COMPUTING

            if session.protocol == MPCProtocol.SECRET_SHARING:
                result = await self._execute_secret_sharing(session)
            elif session.protocol == MPCProtocol.HOMOMORPHIC:
                result = await self._execute_homomorphic(session)
            else:
                result = await self._execute_generic(session)

            session.phase = ComputationPhase.RESULT_DECRYPTION

            for p in session.participants:
                p.status = ParticipantStatus.DONE

            session.final_result = result
            session.phase = ComputationPhase.COMPLETED
            session.completed_at = datetime.utcnow()

            event_bus.emit(build_event(EventType.MPC_COMPUTED, {
                "session_id": session_id,
                "operation": session.operation,
                "participants": len(session.participants),
                "result": result
            }))

            logger.info(f"MPC computation {session_id} completed: {result}")
            return result

        except Exception as e:
            logger.error(f"MPC computation {session_id} failed: {e}")
            session.phase = ComputationPhase.FAILED
            session.error_message = str(e)
            for p in session.participants:
                p.status = ParticipantStatus.FAILED
            return None

    async def _execute_secret_sharing(self, session: MPCSession) -> Any:
        n = len(session.participants)
        threshold = (n // 2) + 1

        all_secret_shares = []
        for i, participant in enumerate(session.participants):
            value = session.inputs[participant.participant_id]
            if isinstance(value, (int, float)):
                int_value = int(value)
                shares = self._engine.secret_share(int_value, n, threshold)
                for j, share in enumerate(shares):
                    session.participants[j].computed_share = session.participants[j].computed_share or 0
                    session.participants[j].computed_share = self._engine.add_encrypted(
                        session.participants[j].computed_share, shares[j]
                    )
                all_secret_shares.append(shares)

        if all_secret_shares:
            total_shares = self._engine.sum_shares(all_secret_shares)

            recon_shares = [(i + 1, total_shares[i]) for i in range(threshold)]
            result = self._engine.secret_reconstruct(recon_shares, threshold)

            return result

        return None

    async def _execute_homomorphic(self, session: MPCSession) -> Any:
        values = list(session.inputs.values())
        if not values:
            return None

        if session.operation == "sum":
            return sum(float(v) for v in values if isinstance(v, (int, float)))
        elif session.operation == "product":
            result = 1.0
            for v in values:
                if isinstance(v, (int, float)):
                    result *= float(v)
            return result
        elif session.operation == "average":
            numeric = [float(v) for v in values if isinstance(v, (int, float))]
            return sum(numeric) / len(numeric) if numeric else 0
        elif session.operation == "max":
            numeric = [float(v) for v in values if isinstance(v, (int, float))]
            return max(numeric) if numeric else None
        elif session.operation == "min":
            numeric = [float(v) for v in values if isinstance(v, (int, float))]
            return min(numeric) if numeric else None
        else:
            return sum(float(v) for v in values if isinstance(v, (int, float)))

    async def _execute_generic(self, session: MPCSession) -> Any:
        values = list(session.inputs.values())
        if not values:
            return None

        return sum(float(v) for v in values if isinstance(v, (int, float)))

    async def wait_for_all_participants(self, session_id: str, timeout: float = 60.0) -> bool:
        start_time = datetime.utcnow()
        while (datetime.utcnow() - start_time).total_seconds() < timeout:
            if self.all_inputs_ready(session_id):
                return True
            await asyncio.sleep(0.5)
        return False

    def get_session_status(self, session_id: str) -> Optional[Dict[str, Any]]:
        session = self._sessions.get(session_id)
        if not session:
            return None

        return {
            "session_id": session.session_id,
            "protocol": session.protocol,
            "phase": session.phase,
            "operation": session.operation,
            "participants_count": len(session.participants),
            "ready_count": sum(1 for p in session.participants if p.status == ParticipantStatus.READY),
            "participants": [
                {
                    "id": p.participant_id,
                    "status": p.status,
                    "has_input": p.encrypted_input is not None
                }
                for p in session.participants
            ],
            "created_at": session.created_at.isoformat(),
            "completed_at": session.completed_at.isoformat() if session.completed_at else None,
            "has_result": session.final_result is not None
        }


class MPCModule:
    def __init__(self):
        self._engine = SecureComputationEngine()
        self._coordinator = MPCProtocolCoordinator(self._engine)
        logger.info("MPCModule initialized")

    @property
    def engine(self) -> SecureComputationEngine:
        return self._engine

    @property
    def coordinator(self) -> MPCProtocolCoordinator:
        return self._coordinator

    async def run_secure_sum(self, participant_inputs: Dict[str, Any],
                              protocol: MPCProtocol = MPCProtocol.SECRET_SHARING) -> Dict[str, Any]:
        participant_ids = list(participant_inputs.keys())
        session = self._coordinator.create_session(
            protocol=protocol,
            operation="sum",
            participant_ids=participant_ids
        )

        for pid, value in participant_inputs.items():
            await self._coordinator.submit_input(session.session_id, pid, value)

        result = await self._coordinator.execute_computation(session.session_id)

        return {
            "session_id": session.session_id,
            "operation": "sum",
            "participants": participant_ids,
            "result": result,
            "status": "completed" if result is not None else "failed"
        }

    async def run_secure_average(self, participant_inputs: Dict[str, Any],
                                  protocol: MPCProtocol = MPCProtocol.HOMOMORPHIC) -> Dict[str, Any]:
        participant_ids = list(participant_inputs.keys())
        session = self._coordinator.create_session(
            protocol=protocol,
            operation="average",
            participant_ids=participant_ids
        )

        for pid, value in participant_inputs.items():
            await self._coordinator.submit_input(session.session_id, pid, value)

        result = await self._coordinator.execute_computation(session.session_id)

        return {
            "session_id": session.session_id,
            "operation": "average",
            "participants": participant_ids,
            "result": result,
            "status": "completed" if result is not None else "failed"
        }

    def get_computation_result(self, session_id: str) -> Optional[Any]:
        session = self._coordinator.get_session(session_id)
        return session.final_result if session else None


mpc_module = MPCModule()
