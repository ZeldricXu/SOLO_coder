"""
Test suite for Zero-Knowledge Proof Verification Module
Focus: Normal flow and exception flow verification
"""
import asyncio
import json
import time
import pytest
from unittest.mock import Mock, MagicMock, patch, AsyncMock, call

from tests.builders import BuilderFactory


class TestZkpNormalFlow:
    """Test suite for ZKP normal verification flows."""

    @pytest.mark.unit
    @pytest.mark.zkp
    @pytest.mark.normal
    def test_verify_valid_proof_success(self, mock_meter_registry):
        """Test that a valid ZKP proof passes verification successfully."""
        proof_builder = BuilderFactory.zkp_proof().with_valid_proof()
        proof_request = proof_builder.build_request_dict()

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()
        mock_mapper.updateById = Mock()

        with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
            from didauth.module.zkp.service import ZkpService
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.verifyProof(proof_request))

            assert result is not None
            assert result.verified is True
            assert result.verifyResult == "SUCCESS"
            assert result.proofId is not None
            assert result.circuitId == proof_request["circuitId"]
            assert result.verifyTimeMs > 0

            assert mock_mapper.insert.call_count == 1
            assert mock_mapper.updateById.call_count == 1

            call_args = mock_mapper.insert.call_args[0][0]
            assert call_args.status == "VERIFYING"

    @pytest.mark.unit
    @pytest.mark.zkp
    @pytest.mark.normal
    def test_verify_proof_with_multiple_public_inputs(self, mock_meter_registry):
        """Test verification with multiple public inputs."""
        proof_builder = BuilderFactory.zkp_proof() \
            .with_valid_proof() \
            .with_public_inputs(["123", "456", "789", "abc", "def"])
        proof_request = proof_builder.build_request_dict()

        mock_mapper = MagicMock()

        with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
            from didauth.module.zkp.service import ZkpService
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.verifyProof(proof_request))

            assert result.verified is True

    @pytest.mark.unit
    @pytest.mark.zkp
    @pytest.mark.normal
    def test_verify_proof_records_metrics(self, mock_meter_registry):
        """Test that verification properly records metrics."""
        proof_builder = BuilderFactory.zkp_proof().with_valid_proof()
        proof_request = proof_builder.build_request_dict()

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()
        mock_mapper.updateById = Mock()

        with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
            from didauth.module.zkp.service import ZkpService
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            asyncio.run(service.verifyProof(proof_request))

            assert mock_meter_registry.timer.called
            assert mock_meter_registry.counter.called

    @pytest.mark.unit
    @pytest.mark.zkp
    @pytest.mark.normal
    def test_get_proof_status_success(self, mock_meter_registry):
        """Test retrieving proof status after verification."""
        proof_builder = BuilderFactory.zkp_proof().with_valid_proof()
        proof_data = proof_builder.build()

        mock_mapper = MagicMock()
        mock_mapper.selectOne = Mock(return_value=MagicMock(
            proofId=proof_data.proof_id,
            status="VERIFIED",
            verifyResult="SUCCESS"
        ))

        with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
            from didauth.module.zkp.service import ZkpService
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.getProofStatus(proof_data.proof_id))

            assert result.proofId == proof_data.proof_id
            assert result.status == "VERIFIED"
            assert mock_mapper.selectOne.call_count == 1

    @pytest.mark.unit
    @pytest.mark.zkp
    @pytest.mark.normal
    def test_verify_proof_circuit_diversity(self, mock_meter_registry):
        """Test verification with different circuit types."""
        circuits = [
            "circuit_groth16_membership",
            "circuit_plonk_range",
            "circuit_zkml_prediction",
            "circuit_identity_reveal",
        ]

        for circuit in circuits:
            proof_builder = BuilderFactory.zkp_proof() \
                .with_valid_proof() \
                .with_circuit(circuit)
            proof_request = proof_builder.build_request_dict()

            mock_mapper = MagicMock()

            with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
                from didauth.module.zkp.service import ZkpService
                service = ZkpService(
                    zkpProofMapper=mock_mapper,
                    meterRegistry=mock_meter_registry
                )

                result = asyncio.run(service.verifyProof(proof_request))

                assert result.circuitId == circuit
                assert result.verified is True

    @pytest.mark.unit
    @pytest.mark.zkp
    @pytest.mark.normal
    def test_proof_status_transitions(self, mock_meter_registry):
        """Test that proof status transitions correctly through verification lifecycle."""
        proof_builder = BuilderFactory.zkp_proof().with_valid_proof()
        proof_request = proof_builder.build_request_dict()

        captured_statuses = []

        mock_mapper = MagicMock()

        def capture_status(entity):
            if hasattr(entity, 'status'):
                captured_statuses.append(entity.status)

        mock_mapper.insert = Mock(side_effect=capture_status)
        mock_mapper.updateById = Mock(side_effect=capture_status)

        with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
            from didauth.module.zkp.service import ZkpService
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            asyncio.run(service.verifyProof(proof_request))

            assert "VERIFYING" in captured_statuses
            assert "VERIFIED" in captured_statuses

    @pytest.mark.integration
    @pytest.mark.zkp
    @pytest.mark.normal
    def test_concurrent_verifications(self, mock_meter_registry):
        """Test that multiple proofs can be verified concurrently."""
        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()
        mock_mapper.updateById = Mock()

        with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
            from didauth.module.zkp.service import ZkpService
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            async def verify_multiple():
                tasks = []
                for i in range(10):
                    proof_builder = BuilderFactory.zkp_proof() \
                        .with_valid_proof() \
                        .with_circuit(f"circuit_{i}")
                    req = proof_builder.build_request_dict()
                    tasks.append(service.verifyProof(req))
                return await asyncio.gather(*tasks)

            results = asyncio.run(verify_multiple())

            assert len(results) == 10
            for result in results:
                assert result.verified is True

            assert mock_mapper.insert.call_count == 10
            assert mock_mapper.updateById.call_count == 10


class TestZkpExceptionFlow:
    """Test suite for ZKP exception and error flows."""

    @pytest.mark.unit
    @pytest.mark.zkp
    @pytest.mark.exception
    def test_verify_empty_proof_data(self, mock_meter_registry):
        """Test that empty proof data raises appropriate exception."""
        proof_builder = BuilderFactory.zkp_proof().with_empty_proof()
        proof_request = proof_builder.build_request_dict()

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()
        mock_mapper.selectOne = Mock(return_value=MagicMock(
            proofId="test_proof",
            status="ERROR",
            errorMessage="Invalid proof data"
        ))

        with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
            from didauth.module.zkp.service import ZkpService
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            with pytest.raises(Exception) as exc_info:
                asyncio.run(service.verifyProof(proof_request))

            mock_mapper.insert.assert_called_once()

    @pytest.mark.unit
    @pytest.mark.zkp
    @pytest.mark.exception
    def test_verify_short_proof_data(self, mock_meter_registry):
        """Test that too-short proof data is rejected."""
        proof_builder = BuilderFactory.zkp_proof().with_short_proof()
        proof_request = proof_builder.build_request_dict()

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()
        mock_mapper.selectOne = Mock(return_value=MagicMock(
            proofId="test_proof",
            status="ERROR"
        ))

        with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
            from didauth.module.zkp.service import ZkpService
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            with pytest.raises(Exception):
                asyncio.run(service.verifyProof(proof_request))

            assert mock_mapper.insert.call_count == 1

    @pytest.mark.unit
    @pytest.mark.zkp
    @pytest.mark.exception
    def test_verify_malformed_json_proof(self, mock_meter_registry):
        """Test that malformed JSON proof data is handled gracefully."""
        proof_builder = BuilderFactory.zkp_proof().with_malformed_json()
        proof_request = proof_builder.build_request_dict()

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()
        mock_mapper.selectOne = Mock(return_value=MagicMock(
            proofId="test_proof",
            status="ERROR"
        ))

        with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
            from didauth.module.zkp.service import ZkpService
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            with pytest.raises(Exception):
                asyncio.run(service.verifyProof(proof_request))

    @pytest.mark.unit
    @pytest.mark.zkp
    @pytest.mark.exception
    def test_verify_invalid_proof_fails(self, mock_meter_registry):
        """Test that an invalid proof fails verification correctly."""
        proof_builder = BuilderFactory.zkp_proof().with_invalid_proof()
        proof_request = proof_builder.build_request_dict()

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()
        mock_mapper.updateById = Mock()

        with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
            from didauth.module.zkp.service import ZkpService
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.verifyProof(proof_request))

            assert result.verified is False
            assert result.verifyResult == "FAILED"

    @pytest.mark.unit
    @pytest.mark.zkp
    @pytest.mark.exception
    def test_get_nonexistent_proof_status(self, mock_meter_registry):
        """Test that querying a non-existent proof raises not found exception."""
        mock_mapper = MagicMock()
        mock_mapper.selectOne = Mock(return_value=None)

        with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
            from didauth.module.zkp.service import ZkpService
            from didauth.common.exception import BusinessException
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            with pytest.raises(BusinessException) as exc_info:
                asyncio.run(service.getProofStatus("nonexistent_proof_id"))

            assert exc_info.value.code == 404

    @pytest.mark.unit
    @pytest.mark.zkp
    @pytest.mark.exception
    def test_verify_proof_database_error(self, mock_meter_registry):
        """Test handling of database errors during verification."""
        proof_builder = BuilderFactory.zkp_proof().with_valid_proof()
        proof_request = proof_builder.build_request_dict()

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock(side_effect=RuntimeError("Database connection failed"))

        with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
            from didauth.module.zkp.service import ZkpService
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            with pytest.raises(RuntimeError):
                asyncio.run(service.verifyProof(proof_request))

    @pytest.mark.unit
    @pytest.mark.zkp
    @pytest.mark.exception
    def test_verify_proof_records_error_status(self, mock_meter_registry):
        """Test that errors during verification are properly recorded."""
        proof_builder = BuilderFactory.zkp_proof().with_valid_proof()
        proof_request = proof_builder.build_request_dict()

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()
        mock_mapper.selectOne = Mock(return_value=MagicMock(
            proofId="test_proof",
            status="ERROR",
            errorMessage="Verification error"
        ))

        with patch('didauth.module.zkp.service.ZkpService.performCircuitVerification',
                   side_effect=RuntimeError("Circuit execution failed")):
            from didauth.module.zkp.service import ZkpService
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            with pytest.raises(Exception):
                asyncio.run(service.verifyProof(proof_request))

            mock_mapper.updateById.assert_called()
            call_args = mock_mapper.updateById.call_args[0][0]
            assert call_args.status == "ERROR"
            assert call_args.errorMessage is not None

    @pytest.mark.unit
    @pytest.mark.zkp
    @pytest.mark.exception
    def test_verify_null_circuit_id(self, mock_meter_registry):
        """Test that null/empty circuit ID is handled properly."""
        proof_builder = BuilderFactory.zkp_proof().with_valid_proof()
        proof_request = proof_builder.build_request_dict()
        proof_request["circuitId"] = ""

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()

        with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
            from didauth.module.zkp.service import ZkpService
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            with pytest.raises(Exception):
                asyncio.run(service.verifyProof(proof_request))

    @pytest.mark.unit
    @pytest.mark.zkp
    @pytest.mark.exception
    def test_verification_timeout_handling(self, mock_meter_registry):
        """Test that verification timeouts are handled gracefully."""
        proof_builder = BuilderFactory.zkp_proof().with_valid_proof()
        proof_request = proof_builder.build_request_dict()

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()
        mock_mapper.selectOne = Mock(return_value=MagicMock(
            proofId="test_proof",
            status="ERROR",
            errorMessage="Verification timed out"
        ))

        from didauth.module.zkp.service import ZkpService
        service = ZkpService(
            zkpProofMapper=mock_mapper,
            meterRegistry=mock_meter_registry
        )

        original_verify = service._ZkpService__performCircuitVerification

        async def slow_verify(*args, **kwargs):
            await asyncio.sleep(5)
            return True

        service._ZkpService__performCircuitVerification = slow_verify

        try:
            with pytest.raises(Exception):
                asyncio.run(asyncio.wait_for(service.verifyProof(proof_request), timeout=0.1))
        finally:
            service._ZkpService__performCircuitVerification = original_verify


class TestZkpEdgeCases:
    """Test suite for ZKP edge cases and boundary conditions."""

    @pytest.mark.unit
    @pytest.mark.zkp
    def test_verify_very_large_proof(self, mock_meter_registry):
        """Test verification with an extremely large proof."""
        large_data = "a" * 1000000
        proof_builder = BuilderFactory.zkp_proof()
        proof_builder._data["proof_data"] = json.dumps({"data": large_data})
        proof_request = proof_builder.build_request_dict()

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()
        mock_mapper.updateById = Mock()

        with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
            from didauth.module.zkp.service import ZkpService
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.verifyProof(proof_request))

            assert result is not None

    @pytest.mark.unit
    @pytest.mark.zkp
    def test_verify_special_characters_in_proof(self, mock_meter_registry):
        """Test verification with special characters in proof data."""
        special_data = json.dumps({
            "proof": "test\x00\x01\x02\n\r\t\\/\"'<>(){}[];,.!@#$%^&*"
        })
        proof_builder = BuilderFactory.zkp_proof()
        proof_builder._data["proof_data"] = special_data
        proof_request = proof_builder.build_request_dict()

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()
        mock_mapper.updateById = Mock()

        with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
            from didauth.module.zkp.service import ZkpService
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            result = asyncio.run(service.verifyProof(proof_request))

            assert result is not None

    @pytest.mark.unit
    @pytest.mark.zkp
    def test_proof_id_uniqueness(self, mock_meter_registry):
        """Test that generated proof IDs are unique."""
        proof_builder = BuilderFactory.zkp_proof().with_valid_proof()

        mock_mapper = MagicMock()
        mock_mapper.insert = Mock()
        mock_mapper.updateById = Mock()

        with patch('didauth.module.zkp.service.ZkpProofMapper', return_value=mock_mapper):
            from didauth.module.zkp.service import ZkpService
            service = ZkpService(
                zkpProofMapper=mock_mapper,
                meterRegistry=mock_meter_registry
            )

            proof_ids = []
            for _ in range(100):
                req = proof_builder.build_request_dict()
                result = asyncio.run(service.verifyProof(req))
                proof_ids.append(result.proofId)

            assert len(proof_ids) == len(set(proof_ids)), "Proof IDs should be unique"
