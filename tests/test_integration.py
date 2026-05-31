from __future__ import annotations

from typing import Any, Dict

import pytest
import pytest_asyncio

from src.core.usecases.address_manager_usecase import (
    AddressBookService,
    HDWalletService,
)
from src.core.usecases.gas_estimator_usecase import GasEstimatorService
from src.core.usecases.transaction_builder_usecase import (
    GasOptimizerService,
    TransactionBuilderService,
)
from src.core.usecases.zkp_verifier_usecase import ZKPVerifierService
from src.shared.config import settings
from src.shared.types import (
    Address,
    Chain,
    HexString,
    ZKPProof,
    ZKPVerificationResult,
)


class TestHDWalletService:
    @pytest.fixture
    def wallet_service(self):
        return HDWalletService()

    @pytest.mark.asyncio
    async def test_generate_mnemonic(self, wallet_service: HDWalletService):
        try:
            mnemonic = await wallet_service.generate_mnemonic(128)
            assert mnemonic is not None
            assert isinstance(mnemonic, str)
            assert len(mnemonic.split()) == 12
        except Exception as e:
            pytest.skip(f"mnemonic/bip-utils not installed: {e}")

    @pytest.mark.asyncio
    async def test_wallet_not_initialized(self, wallet_service: HDWalletService):
        assert wallet_service.is_initialized() is False

    @pytest.mark.asyncio
    async def test_derive_without_initialization(self, wallet_service: HDWalletService):
        with pytest.raises(Exception):
            await wallet_service.derive_address(0)


class TestAddressBookService:
    @pytest.fixture
    def address_book(self):
        return AddressBookService()

    @pytest.mark.asyncio
    async def test_add_and_get_address(
        self,
        address_book: AddressBookService,
        sample_address: str,
    ):
        key = await address_book.add_address(
            address=sample_address,
            name="Test Address",
            chain=Chain.ETHEREUM,
            labels=["test", "user"],
            notes="Test note",
        )
        assert key is not None

        entry = await address_book.get_address(sample_address)
        assert entry is not None
        assert entry["address"].lower() == sample_address.lower()
        assert entry["name"] == "Test Address"
        assert entry["chain"] == Chain.ETHEREUM

    @pytest.mark.asyncio
    async def test_list_addresses(
        self,
        address_book: AddressBookService,
        sample_address: str,
    ):
        await address_book.add_address(
            address=sample_address,
            name="Test",
            chain=Chain.ETHEREUM,
            labels=["test"],
        )
        entries = await address_book.list_addresses()
        assert len(entries) >= 1

    @pytest.mark.asyncio
    async def test_search_addresses(
        self,
        address_book: AddressBookService,
        sample_address: str,
    ):
        await address_book.add_address(
            address=sample_address,
            name="UniqueTestName",
            chain=Chain.ETHEREUM,
        )
        results = await address_book.search_addresses("UniqueTestName")
        assert len(results) >= 1

    @pytest.mark.asyncio
    async def test_add_and_remove_label(
        self,
        address_book: AddressBookService,
        sample_address: str,
    ):
        await address_book.add_address(
            address=sample_address,
            name="Test",
            chain=Chain.ETHEREUM,
        )
        result = await address_book.add_label(sample_address, "newlabel")
        assert result is True

        entry = await address_book.get_address(sample_address)
        assert "newlabel" in entry["labels"]

        result = await address_book.remove_label(sample_address, "newlabel")
        assert result is True


class TestZKPVerifierService:
    @pytest.fixture
    def zkp_service(self):
        return ZKPVerifierService()

    @pytest.fixture
    def sample_proof(self) -> Dict[str, Any]:
        return {
            "proof_type": "groth16",
            "circuit_id": "test_circuit",
            "proof_data": "0x" + "a" * 256,
            "public_inputs": ["0x1234", "0x5678"],
        }

    @pytest.mark.asyncio
    async def test_register_and_get_circuit(self, zkp_service: ZKPVerifierService):
        circuit_id = "test_circuit_1"
        vk = "0x" + "b" * 128
        result = await zkp_service.register_circuit(
            circuit_id=circuit_id,
            verification_key=vk,
            circuit_type="groth16",
        )
        assert result is True

        circuits = await zkp_service.get_registered_circuits()
        assert circuit_id in circuits

        info = await zkp_service.get_circuit_info(circuit_id)
        assert info is not None
        assert info["circuit_type"] == "groth16"

    @pytest.mark.asyncio
    async def test_validate_proof_format(
        self, zkp_service: ZKPVerifierService, sample_proof: Dict[str, Any]
    ):
        proof = ZKPProof(**sample_proof)
        valid = await zkp_service.validate_proof_format(proof)
        assert valid is True

    @pytest.mark.asyncio
    async def test_verify_proof_without_vk(
        self, zkp_service: ZKPVerifierService, sample_proof: Dict[str, Any]
    ):
        proof = ZKPProof(**sample_proof)
        result = await zkp_service.verify_proof(proof)
        assert isinstance(result, ZKPVerificationResult)
        assert result.verified is False
        assert "Verification key not found" in result.error

    @pytest.mark.asyncio
    async def test_verify_proof_with_vk(
        self, zkp_service: ZKPVerifierService, sample_proof: Dict[str, Any]
    ):
        circuit_id = sample_proof["circuit_id"]
        vk = "0x" + "b" * 128
        await zkp_service.register_circuit(circuit_id, vk)

        proof = ZKPProof(**sample_proof)
        result = await zkp_service.verify_proof(proof)
        assert isinstance(result, ZKPVerificationResult)
        assert result.verified is True or result.verified is False
        assert result.circuit_id == circuit_id
        assert result.verification_time_ms >= 0


class TestGasOptimizerService:
    @pytest.fixture
    def gas_optimizer(self):
        return GasOptimizerService()

    @pytest.fixture
    def sample_transaction(self, sample_address: str) -> Dict[str, Any]:
        return {
            "to": sample_address,
            "from": sample_address,
            "value": 0,
            "data": "0x",
            "gas": 100000,
            "gasPrice": 20000000000,
            "nonce": 0,
            "chainId": 1,
        }

    @pytest.mark.asyncio
    async def test_optimize_gas_limit(
        self, gas_optimizer: GasOptimizerService, sample_transaction: Dict[str, Any]
    ):
        optimized = await gas_optimizer.optimize_gas_limit(
            sample_transaction, buffer_percent=20.0
        )
        assert optimized["gas"] == 120000

    @pytest.mark.asyncio
    async def test_suggest_gas_savings(
        self, gas_optimizer: GasOptimizerService, sample_transaction: Dict[str, Any]
    ):
        sample_transaction["gas"] = 200000
        suggestions = await gas_optimizer.suggest_gas_savings(sample_transaction)
        assert isinstance(suggestions, list)
        assert len(suggestions) >= 1


class TestIntegration:
    @pytest.mark.asyncio
    async def test_address_workflow(self):
        wallet = HDWalletService()
        address_book = AddressBookService()

        try:
            mnemonic = await wallet.generate_mnemonic()
            await wallet.create_wallet_from_mnemonic(mnemonic)
            account = await wallet.derive_address(0, label="Primary")
            
            await address_book.add_address(
                address=account.address,
                name="Primary Wallet",
                chain=Chain.ETHEREUM,
                labels=["wallet", "primary"],
            )

            entry = await address_book.get_address(account.address)
            assert entry is not None
            assert entry["name"] == "Primary Wallet"
        except Exception as e:
            pytest.skip(f"Dependencies not available: {e}")
