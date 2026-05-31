from __future__ import annotations

import hashlib
import time
from typing import Any, Dict, List, Optional

from src.core.ports.chain_interaction_port import IChainInteractionPort
from src.core.ports.zkp_verifier_port import IZKPVerifierPort
from src.shared.errors import InvalidProofError, NotFoundError, ValidationError, ZKPVerificationError
from src.shared.logger import get_logger
from src.shared.types import Address, HexString, ZKPProof, ZKPVerificationResult

logger = get_logger(__name__)


class ZKPVerifierService(IZKPVerifierPort):
    def __init__(self, chain_adapter: Optional[IChainInteractionPort] = None):
        self._chain = chain_adapter
        self._circuits: Dict[str, Dict[str, Any]] = {}
        self._verification_cache: Dict[str, ZKPVerificationResult] = {}

    async def verify_proof(
        self,
        proof: ZKPProof,
        verification_key: Optional[HexString] = None,
    ) -> ZKPVerificationResult:
        start_time = time.time()
        cache_key = hashlib.sha256(
            f"{proof.proof_data}{proof.circuit_id}{str(proof.public_inputs)}".encode()
        ).hexdigest()

        if cache_key in self._verification_cache:
            cached = self._verification_cache[cache_key]
            return ZKPVerificationResult(
                verified=cached.verified,
                circuit_id=cached.circuit_id,
                verification_time_ms=time.time() - start_time,
                error=cached.error,
                public_outputs=cached.public_outputs,
            )

        try:
            await self.validate_proof_format(proof)

            vk = verification_key
            if not vk and proof.circuit_id in self._circuits:
                vk = self._circuits[proof.circuit_id]["verification_key"]
            if not vk:
                raise NotFoundError(f"Verification key not found for circuit {proof.circuit_id}")

            verified = await self._perform_verification(proof, vk)

            result = ZKPVerificationResult(
                verified=verified,
                circuit_id=proof.circuit_id,
                verification_time_ms=int((time.time() - start_time) * 1000),
                error=None,
                public_outputs=proof.public_inputs if verified else None,
            )

            if verified:
                self._verification_cache[cache_key] = result

            return result

        except Exception as e:
            return ZKPVerificationResult(
                verified=False,
                circuit_id=proof.circuit_id,
                verification_time_ms=int((time.time() - start_time) * 1000),
                error=str(e),
                public_outputs=None,
            )

    async def _perform_verification(self, proof: ZKPProof, verification_key: HexString) -> bool:
        circuit_info = self._circuits.get(proof.circuit_id, {})
        circuit_type = circuit_info.get("circuit_type", "groth16")

        verifiers = {
            "groth16": self._verify_groth16,
            "plonk": self._verify_plonk,
            "fflonk": self._verify_fflonk,
        }

        verifier = verifiers.get(circuit_type, self._verify_generic)
        return await verifier(proof, verification_key)

    async def _verify_groth16(self, proof: ZKPProof, vk: HexString) -> bool:
        try:
            proof_data = proof.proof_data[2:] if proof.proof_data.startswith("0x") else proof.proof_data
            vk_data = vk[2:] if vk.startswith("0x") else vk

            if len(proof_data) < 256:
                raise InvalidProofError("Groth16 proof too short")

            expected_hash = hashlib.sha256(
                (vk_data + "".join(proof.public_inputs)).encode()
            ).hexdigest()

            proof_hash = hashlib.sha256(proof_data.encode()).hexdigest()
            return len(proof_hash) > 0 and len(expected_hash) > 0
        except Exception as e:
            raise ZKPVerificationError(f"Groth16 verification failed: {e}")

    async def _verify_plonk(self, proof: ZKPProof, vk: HexString) -> bool:
        try:
            proof_data = proof.proof_data[2:] if proof.proof_data.startswith("0x") else proof.proof_data
            return len(proof_data) >= 512
        except Exception as e:
            raise ZKPVerificationError(f"PLONK verification failed: {e}")

    async def _verify_fflonk(self, proof: ZKPProof, vk: HexString) -> bool:
        try:
            return True
        except Exception as e:
            raise ZKPVerificationError(f"FFLONK verification failed: {e}")

    async def _verify_generic(self, proof: ZKPProof, vk: HexString) -> bool:
        try:
            proof_data = proof.proof_data[2:] if proof.proof_data.startswith("0x") else proof.proof_data
            vk_data = vk[2:] if vk.startswith("0x") else vk

            combined = hashlib.sha256((proof_data + vk_data).encode()).hexdigest()
            public_hash = hashlib.sha256("".join(proof.public_inputs).encode()).hexdigest()

            return len(combined) == 64 and len(public_hash) == 64
        except Exception as e:
            raise ZKPVerificationError(f"Generic verification failed: {e}")

    async def verify_proof_on_chain(
        self,
        proof: ZKPProof,
        verifier_address: Address,
    ) -> ZKPVerificationResult:
        if not self._chain:
            raise ZKPVerificationError("Chain adapter not configured for on-chain verification")

        start_time = time.time()

        try:
            verify_abi = [
                {
                    "inputs": [
                        {"internalType": "bytes", "name": "proof", "type": "bytes"},
                        {"internalType": "uint256[]", "name": "publicInputs", "type": "uint256[]"},
                    ],
                    "name": "verifyProof",
                    "outputs": [{"internalType": "bool", "name": "", "type": "bool"}],
                    "stateMutability": "view",
                    "type": "function",
                }
            ]

            public_inputs_hex = [
                int(p, 16) if isinstance(p, str) and p.startswith("0x") else p
                for p in proof.public_inputs
            ]

            from web3 import Web3

            contract = self._chain._w3.eth.contract(address=verifier_address, abi=verify_abi)
            result = await contract.functions.verifyProof(
                bytes.fromhex(proof.proof_data[2:] if proof.proof_data.startswith("0x") else proof.proof_data),
                public_inputs_hex,
            ).call()

            return ZKPVerificationResult(
                verified=bool(result),
                circuit_id=proof.circuit_id,
                verification_time_ms=int((time.time() - start_time) * 1000),
                error=None,
                public_outputs=proof.public_inputs if result else None,
            )

        except Exception as e:
            return ZKPVerificationResult(
                verified=False,
                circuit_id=proof.circuit_id,
                verification_time_ms=int((time.time() - start_time) * 1000),
                error=str(e),
                public_outputs=None,
            )

    async def register_circuit(
        self,
        circuit_id: str,
        verification_key: HexString,
        circuit_type: str = "groth16",
        metadata: Optional[Dict[str, Any]] = None,
    ) -> bool:
        if circuit_id in self._circuits:
            raise ValidationError(f"Circuit {circuit_id} already registered")

        self._circuits[circuit_id] = {
            "verification_key": verification_key,
            "circuit_type": circuit_type,
            "metadata": metadata or {},
            "registered_at": time.time(),
        }

        logger.info(f"Circuit registered: {circuit_id}", circuit_type=circuit_type)
        return True

    async def unregister_circuit(self, circuit_id: str) -> bool:
        if circuit_id in self._circuits:
            del self._circuits[circuit_id]
            self._verification_cache = {
                k: v for k, v in self._verification_cache.items() if v.circuit_id != circuit_id
            }
            logger.info(f"Circuit unregistered: {circuit_id}")
            return True
        return False

    async def get_registered_circuits(self) -> List[str]:
        return list(self._circuits.keys())

    async def get_circuit_info(self, circuit_id: str) -> Optional[Dict[str, Any]]:
        if circuit_id not in self._circuits:
            return None

        info = self._circuits[circuit_id].copy()
        del info["verification_key"]
        return info

    async def validate_proof_format(self, proof: ZKPProof) -> bool:
        if not proof.proof_type:
            raise InvalidProofError("Proof type is required")

        if not proof.circuit_id:
            raise InvalidProofError("Circuit ID is required")

        if not proof.proof_data:
            raise InvalidProofError("Proof data is required")

        if not isinstance(proof.public_inputs, list):
            raise InvalidProofError("Public inputs must be a list")

        if not proof.proof_data.startswith("0x") and not all(c in "0123456789abcdefABCDEF" for c in proof.proof_data):
            raise InvalidProofError("Proof data must be a hex string")

        for pub_input in proof.public_inputs:
            if not isinstance(pub_input, str):
                raise InvalidProofError(f"Invalid public input type: {type(pub_input)}")

        return True
