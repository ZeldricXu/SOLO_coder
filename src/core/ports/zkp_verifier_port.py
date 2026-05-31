from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional

from src.shared.types import Address, HexString, ZKPProof, ZKPVerificationResult


class IZKPVerifierPort(ABC):
    @abstractmethod
    async def verify_proof(
        self,
        proof: ZKPProof,
        verification_key: Optional[HexString] = None,
    ) -> ZKPVerificationResult: ...

    @abstractmethod
    async def verify_proof_on_chain(
        self,
        proof: ZKPProof,
        verifier_address: Address,
    ) -> ZKPVerificationResult: ...

    @abstractmethod
    async def register_circuit(
        self,
        circuit_id: str,
        verification_key: HexString,
        circuit_type: str = "groth16",
        metadata: Optional[Dict[str, Any]] = None,
    ) -> bool: ...

    @abstractmethod
    async def unregister_circuit(self, circuit_id: str) -> bool: ...

    @abstractmethod
    async def get_registered_circuits(self) -> List[str]: ...

    @abstractmethod
    async def get_circuit_info(self, circuit_id: str) -> Optional[Dict[str, Any]]: ...

    @abstractmethod
    async def validate_proof_format(self, proof: ZKPProof) -> bool: ...


class IProofStore(ABC):
    @abstractmethod
    async def store_proof(self, proof: ZKPProof, result: ZKPVerificationResult) -> str: ...

    @abstractmethod
    async def get_proof(self, proof_id: str) -> Optional[Dict[str, Any]]: ...

    @abstractmethod
    async def list_proofs(self, circuit_id: Optional[str] = None, limit: int = 100) -> List[Dict[str, Any]]: ...
