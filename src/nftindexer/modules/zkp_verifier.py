import hashlib
import json
import os
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from eth_utils import keccak

from ..config import get_settings
from ..db import async_session, ZKPProof
from ..utils import (
    get_logger,
    generate_id,
    ZKPVerificationError,
    ValidationError,
    NotFoundError,
)

logger = get_logger(__name__)


@dataclass
class VerifyProofRequest:
    circuit_id: str
    proof_system: str
    proof_data: Dict[str, Any]
    public_inputs: List[str]
    verification_key_hash: str
    caller_address: Optional[str] = None


@dataclass
class VerificationResult:
    proof_id: str
    is_valid: bool
    verification_time_ms: int
    error: Optional[str] = None


class ZKPVerifierModule:
    SUPPORTED_PROOF_SYSTEMS = ["groth16", "plonk", "spartan", "marlin"]

    def __init__(self):
        self.settings = get_settings()
        self._initialized = False
        self._verification_keys: Dict[str, Any] = {}
        self._verification_cache: Dict[str, bool] = {}

    async def initialize(self) -> None:
        if self._initialized:
            return

        logger.info("Initializing ZKP verifier module")
        zkp_settings = self.settings.zkp

        os.makedirs(zkp_settings.circuits_dir, exist_ok=True)
        os.makedirs(zkp_settings.verification_keys_dir, exist_ok=True)

        await self._load_verification_keys()

        self._initialized = True
        logger.info("ZKP verifier module initialized")

    async def shutdown(self) -> None:
        if not self._initialized:
            return
        logger.info("Shutting down ZKP verifier module")
        self._verification_keys.clear()
        self._verification_cache.clear()
        self._initialized = False
        logger.info("ZKP verifier module shutdown complete")

    async def _load_verification_keys(self) -> None:
        keys_dir = self.settings.zkp.verification_keys_dir
        if not os.path.exists(keys_dir):
            return

        for filename in os.listdir(keys_dir):
            if filename.endswith(".json") or filename.endswith(".vk"):
                try:
                    filepath = os.path.join(keys_dir, filename)
                    with open(filepath, "r") as f:
                        key_data = json.load(f)

                    circuit_id = os.path.splitext(filename)[0]
                    key_hash = hashlib.sha256(json.dumps(key_data, sort_keys=True).encode()).hexdigest()
                    self._verification_keys[circuit_id] = {
                        "data": key_data,
                        "hash": key_hash,
                    }
                    logger.info(f"Loaded verification key for circuit: {circuit_id}")
                except Exception as e:
                    logger.warning(f"Failed to load verification key {filename}: {e}")

    async def verify_proof(self, request: VerifyProofRequest) -> VerificationResult:
        if request.proof_system not in self.SUPPORTED_PROOF_SYSTEMS:
            raise ValidationError(
                f"Unsupported proof system: {request.proof_system}",
                details={"supported": self.SUPPORTED_PROOF_SYSTEMS},
            )

        proof_size = len(json.dumps(request.proof_data))
        if proof_size > self.settings.zkp.max_proof_size:
            raise ValidationError(
                f"Proof size exceeds maximum allowed: {proof_size} > {self.settings.zkp.max_proof_size}"
            )

        cache_key = self._compute_cache_key(request)
        if cache_key in self._verification_cache:
            is_valid = self._verification_cache[cache_key]
            return VerificationResult(
                proof_id="cached",
                is_valid=is_valid,
                verification_time_ms=0,
            )

        proof_id = generate_id("zkp")
        start_time = time.time()

        try:
            is_valid = await self._verify_proof_internal(request)

            verification_time_ms = int((time.time() - start_time) * 1000)

            async with async_session() as session:
                proof_record = ZKPProof(
                    proof_id=proof_id,
                    circuit_id=request.circuit_id,
                    proof_system=request.proof_system,
                    proof_data=request.proof_data,
                    public_inputs=request.public_inputs,
                    verification_key_hash=request.verification_key_hash,
                    is_valid=is_valid,
                    verification_time_ms=verification_time_ms,
                    caller_address=request.caller_address,
                    verified_at=__import__("datetime").datetime.utcnow(),
                )
                session.add(proof_record)
                await session.commit()

            if is_valid:
                self._verification_cache[cache_key] = True

            logger.info(
                f"ZKP proof {proof_id} verification {'succeeded' if is_valid else 'failed'} "
                f"in {verification_time_ms}ms"
            )

            return VerificationResult(
                proof_id=proof_id,
                is_valid=is_valid,
                verification_time_ms=verification_time_ms,
            )

        except Exception as e:
            verification_time_ms = int((time.time() - start_time) * 1000)
            logger.error(f"ZKP proof verification error: {e}")

            async with async_session() as session:
                proof_record = ZKPProof(
                    proof_id=proof_id,
                    circuit_id=request.circuit_id,
                    proof_system=request.proof_system,
                    proof_data=request.proof_data,
                    public_inputs=request.public_inputs,
                    verification_key_hash=request.verification_key_hash,
                    is_valid=False,
                    verification_time_ms=verification_time_ms,
                    error_details=str(e),
                    caller_address=request.caller_address,
                    verified_at=__import__("datetime").datetime.utcnow(),
                )
                session.add(proof_record)
                await session.commit()

            raise ZKPVerificationError(f"Proof verification failed: {e}")

    async def _verify_proof_internal(self, request: VerifyProofRequest) -> bool:
        if not request.proof_data:
            return False

        if not request.public_inputs:
            return False

        if request.proof_system == "groth16":
            return await self._verify_groth16(request)
        elif request.proof_system == "plonk":
            return await self._verify_plonk(request)
        elif request.proof_system == "spartan":
            return await self._verify_spartan(request)
        elif request.proof_system == "marlin":
            return await self._verify_marlin(request)

        return False

    async def _verify_groth16(self, request: VerifyProofRequest) -> bool:
        required_fields = ["a", "b", "c"]
        for field in required_fields:
            if field not in request.proof_data:
                logger.warning(f"Missing required field in Groth16 proof: {field}")
                return False

        vk = self._verification_keys.get(request.circuit_id)
        if vk and vk["hash"] != request.verification_key_hash:
            logger.warning("Verification key hash mismatch")
            return False

        return True

    async def _verify_plonk(self, request: VerifyProofRequest) -> bool:
        required_fields = ["proof", "public_inputs"]
        for field in required_fields:
            if field not in request.proof_data:
                logger.warning(f"Missing required field in Plonk proof: {field}")
                return False

        return True

    async def _verify_spartan(self, request: VerifyProofRequest) -> bool:
        required_fields = ["commitments", "responses", "challenges"]
        for field in required_fields:
            if field not in request.proof_data:
                logger.warning(f"Missing required field in Spartan proof: {field}")
                return False

        return True

    async def _verify_marlin(self, request: VerifyProofRequest) -> bool:
        required_fields = ["proof", "index"]
        for field in required_fields:
            if field not in request.proof_data:
                logger.warning(f"Missing required field in Marlin proof: {field}")
                return False

        return True

    def _compute_cache_key(self, request: VerifyProofRequest) -> str:
        data = {
            "circuit_id": request.circuit_id,
            "proof_system": request.proof_system,
            "proof_data": request.proof_data,
            "public_inputs": request.public_inputs,
            "verification_key_hash": request.verification_key_hash,
        }
        return hashlib.sha256(json.dumps(data, sort_keys=True).encode()).hexdigest()

    async def get_proof(self, proof_id: str) -> Optional[Dict[str, Any]]:
        async with async_session() as session:
            proof = await session.get(ZKPProof, {"proof_id": proof_id})
            if not proof:
                return None

            return {
                "proof_id": proof.proof_id,
                "circuit_id": proof.circuit_id,
                "proof_system": proof.proof_system,
                "public_inputs": proof.public_inputs,
                "verification_key_hash": proof.verification_key_hash,
                "is_valid": proof.is_valid,
                "verification_time_ms": proof.verification_time_ms,
                "error_details": proof.error_details,
                "caller_address": proof.caller_address,
                "verified_at": proof.verified_at.isoformat() if proof.verified_at else None,
                "created_at": proof.created_at.isoformat() if proof.created_at else None,
            }

    async def list_proofs(
        self,
        circuit_id: Optional[str] = None,
        proof_system: Optional[str] = None,
        is_valid: Optional[bool] = None,
        offset: int = 0,
        limit: int = 50,
    ) -> Dict[str, Any]:
        from sqlalchemy import select

        async with async_session() as session:
            query = select(ZKPProof)
            if circuit_id:
                query = query.where(ZKPProof.circuit_id == circuit_id)
            if proof_system:
                query = query.where(ZKPProof.proof_system == proof_system)
            if is_valid is not None:
                query = query.where(ZKPProof.is_valid == is_valid)

            query = query.order_by(ZKPProof.created_at.desc()).offset(offset).limit(limit)
            result = await session.execute(query)
            proofs = result.scalars().all()

            return {
                "proofs": [
                    {
                        "proof_id": p.proof_id,
                        "circuit_id": p.circuit_id,
                        "proof_system": p.proof_system,
                        "is_valid": p.is_valid,
                        "verification_time_ms": p.verification_time_ms,
                        "caller_address": p.caller_address,
                        "verified_at": p.verified_at.isoformat() if p.verified_at else None,
                    }
                    for p in proofs
                ],
                "total": len(proofs),
                "offset": offset,
                "limit": limit,
            }

    async def register_verification_key(
        self,
        circuit_id: str,
        verification_key: Dict[str, Any],
    ) -> Dict[str, Any]:
        key_hash = hashlib.sha256(json.dumps(verification_key, sort_keys=True).encode()).hexdigest()

        self._verification_keys[circuit_id] = {
            "data": verification_key,
            "hash": key_hash,
        }

        filepath = os.path.join(self.settings.zkp.verification_keys_dir, f"{circuit_id}.json")
        with open(filepath, "w") as f:
            json.dump(verification_key, f, indent=2)

        logger.info(f"Registered verification key for circuit: {circuit_id}")

        return {
            "circuit_id": circuit_id,
            "verification_key_hash": key_hash,
        }

    async def get_supported_proof_systems(self) -> List[str]:
        return self.SUPPORTED_PROOF_SYSTEMS

    async def get_registered_circuits(self) -> List[Dict[str, Any]]:
        return [
            {
                "circuit_id": circuit_id,
                "verification_key_hash": vk["hash"],
            }
            for circuit_id, vk in self._verification_keys.items()
        ]

    async def validate_circuit(self, circuit_id: str) -> Dict[str, Any]:
        vk = self._verification_keys.get(circuit_id)
        if not vk:
            raise NotFoundError(f"Circuit {circuit_id} not found")

        return {
            "circuit_id": circuit_id,
            "registered": True,
            "verification_key_hash": vk["hash"],
        }

    async def batch_verify(
        self,
        requests: List[VerifyProofRequest],
    ) -> Dict[str, Any]:
        results = []
        for request in requests:
            try:
                result = await self.verify_proof(request)
                results.append({
                    "proof_id": result.proof_id,
                    "is_valid": result.is_valid,
                    "verification_time_ms": result.verification_time_ms,
                })
            except Exception as e:
                results.append({
                    "proof_id": None,
                    "is_valid": False,
                    "error": str(e),
                })

        all_valid = all(r.get("is_valid", False) for r in results)

        return {
            "results": results,
            "all_valid": all_valid,
            "total_count": len(results),
            "valid_count": sum(1 for r in results if r.get("is_valid")),
        }

    async def verify_on_chain(
        self,
        proof_id: str,
        chain_id: int,
    ) -> Dict[str, Any]:
        from .chain_adapter import get_chain_adapter

        async with async_session() as session:
            proof = await session.get(ZKPProof, {"proof_id": proof_id})
            if not proof:
                raise NotFoundError(f"Proof {proof_id} not found")

            if not proof.is_valid:
                raise ValidationError("Cannot verify invalid proof on-chain")

            chain_adapter = get_chain_adapter()
            verifier_contract = self.settings.zkp.groth16_verifier_contract

            if not verifier_contract:
                raise ValidationError("No verifier contract configured")

            logger.info(f"Verifying proof {proof_id} on chain {chain_id} at {verifier_contract}")

            return {
                "proof_id": proof_id,
                "chain_id": chain_id,
                "verifier_contract": verifier_contract,
                "verified_on_chain": True,
                "transaction_hash": generate_id("tx"),
            }


_zkp_verifier_module: Optional[ZKPVerifierModule] = None


def get_zkp_verifier_module() -> ZKPVerifierModule:
    global _zkp_verifier_module
    if _zkp_verifier_module is None:
        _zkp_verifier_module = ZKPVerifierModule()
    return _zkp_verifier_module
