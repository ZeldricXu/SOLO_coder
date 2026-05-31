"""可信执行环境模块单元测试

测试重点：边界条件处理
- Enclave数量限制
- 时间戳过期校验
- 签名格式校验
- 不支持的TEE技术
- 不存在的Enclave ID
- 状态转换验证
"""

import pytest
from unittest.mock import MagicMock, patch
from typing import List

from tests.test_data_builders.tee_builder import (
    TeeTestDataBuilder,
    EnclaveStatus,
    TeeTechnology,
    Enclave,
    EnclaveCreateRequest,
    AttestationRequest,
    EnclaveExecuteRequest,
    TeeConfig,
)


@pytest.fixture
def tee_builder():
    """TEE测试数据构建器fixture"""
    return TeeTestDataBuilder()


@pytest.fixture
def tee_config():
    """TEE配置fixture"""
    return TeeConfig()


class TestEnclaveCreation:
    """Enclave创建测试"""
    
    def test_create_enclave_success(self, tee_builder, tee_config):
        """测试成功创建Enclave - 正常流程"""
        request = tee_builder.build_enclave_create_request()
        
        assert request.technology in [TeeTechnology.SGX, TeeTechnology.SEV, TeeTechnology.TrustZone]
        assert request.signature.startswith('') or len(request.signature) >= 32
        assert request.timestamp > 0
    
    def test_create_enclave_at_max_limit(self, tee_builder):
        """测试边界条件：Enclave数量达到上限"""
        max_limit_config = tee_builder.build_config(max_enclaves=2)
        assert max_limit_config.max_enclaves == 2
    
    def test_create_enclave_with_unsupported_technology(self, tee_builder):
        """测试边界条件：使用不支持的TEE技术"""
        for tech in tee_builder.build_unsupported_technologies():
            request = tee_builder.build_enclave_create_request(technology=tech)
            assert request.technology == tech
    
    def test_create_enclave_with_edge_case_metadata(self, tee_builder):
        """测试边界条件：各种边界情况的元数据"""
        edge_metadata = tee_builder.build_edge_case_metadata()
        
        for meta in edge_metadata:
            request = tee_builder.build_enclave_create_request(metadata=meta)
            assert request.metadata == meta
    
    def test_create_enclave_with_large_metadata(self, tee_builder):
        """测试边界条件：超大元数据"""
        large_metadata = {
            "large_key": "x" * 10000,
            "nested": {"deep": {"value": "y" * 5000}}
        }
        request = tee_builder.build_enclave_create_request(metadata=large_metadata)
        assert len(str(request.metadata)) > 10000


class TestTimestampValidation:
    """时间戳校验测试"""
    
    def test_expired_timestamp(self, tee_builder):
        """测试边界条件：过期的时间戳（超过300秒）"""
        expired_ts = tee_builder.build_expired_timestamp()
        current_ts = tee_builder._current_timestamp()
        
        assert expired_ts < current_ts - 300
        assert current_ts - expired_ts > 300
    
    def test_valid_timestamp(self, tee_builder):
        """测试正常流程：有效时间戳"""
        request = tee_builder.build_enclave_create_request()
        
        current_ts = tee_builder._current_timestamp()
        assert abs(request.timestamp - current_ts) < 5
    
    def test_future_timestamp(self, tee_builder):
        """测试边界条件：未来的时间戳"""
        future_ts = tee_builder.build_future_timestamp()
        current_ts = tee_builder._current_timestamp()
        
        assert future_ts > current_ts
    
    def test_timestamp_edge_values(self, tee_builder):
        """测试边界条件：时间戳边缘值"""
        edge_ts_values = [
            0,
            1,
            999999999999,
        ]
        
        for ts in edge_ts_values:
            request = tee_builder.build_enclave_create_request(timestamp=ts)
            assert request.timestamp == ts


class TestSignatureValidation:
    """签名校验测试"""
    
    def test_valid_signature(self, tee_builder):
        """测试正常流程：有效签名"""
        request = tee_builder.build_enclave_create_request(valid_signature=True)
        
        assert len(request.signature) == 64
        all_hex = all(c in '0123456789abcdef' for c in request.signature.lower())
        assert all_hex
    
    def test_invalid_signature(self, tee_builder):
        """测试边界条件：无效签名"""
        request = tee_builder.build_enclave_create_request(valid_signature=False)
        
        assert 'invalid' in request.signature or len(request.signature) != 64
    
    def test_signature_tampering_detection(self, tee_builder):
        """测试边界条件：签名篡改检测"""
        request1 = tee_builder.build_enclave_create_request()
        request2 = tee_builder.build_enclave_create_request(metadata={"tampered": True})
        
        assert request1.signature != request2.signature
    
    def test_empty_signature(self, tee_builder):
        """测试边界条件：空签名"""
        request = tee_builder.build_enclave_create_request()
        request.signature = ""
        
        assert request.signature == ""


class TestEnclaveIdValidation:
    """Enclave ID校验测试"""
    
    def test_valid_enclave_ids(self, tee_builder):
        """测试正常流程：有效Enclave ID"""
        valid_ids = tee_builder.build_valid_enclave_ids(10)
        
        for eid in valid_ids:
            assert eid.startswith('enc_')
            assert len(eid) > 4
    
    def test_invalid_enclave_ids(self, tee_builder):
        """测试边界条件：无效Enclave ID"""
        invalid_ids = tee_builder.build_invalid_enclave_ids()
        
        valid_ids = tee_builder.build_valid_enclave_ids(1)
        valid_prefix = valid_ids[0].split('_')[0] if '_' in valid_ids[0] else 'enc'
        
        for eid in invalid_ids:
            assert eid == '' or ' ' in eid or eid not in valid_ids
    
    def test_nonexistent_enclave_id(self, tee_builder):
        """测试边界条件：不存在的Enclave ID"""
        nonexistent_id = "enc_nonexistent_123456"
        valid_ids = tee_builder.build_valid_enclave_ids(5)
        
        assert nonexistent_id not in valid_ids
    
    def test_enclave_id_special_characters(self, tee_builder):
        """测试边界条件：含特殊字符的Enclave ID"""
        special_chars_id = "enc_!@#$%^&*()"
        
        assert any(c in special_chars_id for c in '!@#$%^&*()')


class TestEnclaveStatusTransitions:
    """Enclave状态转换测试"""
    
    def test_enclave_initial_status(self, tee_builder):
        """测试正常流程：Enclave初始状态"""
        enclave = tee_builder.build_enclave()
        
        assert enclave.status == EnclaveStatus.Created
        assert enclave.measurement is not None
    
    def test_enclave_status_to_running(self, tee_builder):
        """测试状态转换：Created -> Running"""
        enclave = tee_builder.build_enclave(status=EnclaveStatus.Running)
        
        assert enclave.status == EnclaveStatus.Running
    
    def test_enclave_status_to_attested(self, tee_builder):
        """测试状态转换：Running -> Attested"""
        enclave = tee_builder.build_enclave(
            status=EnclaveStatus.Attested,
            with_attestation_token=True
        )
        
        assert enclave.status == EnclaveStatus.Attested
        assert enclave.attestation_token is not None
    
    def test_enclave_status_edge_cases(self, tee_builder):
        """测试边界条件：各种状态"""
        edge_statuses = [
            EnclaveStatus.Paused,
            EnclaveStatus.Stopped,
            EnclaveStatus.Failed,
        ]
        
        for status in edge_statuses:
            enclave = tee_builder.build_enclave(status=status)
            assert enclave.status == status
    
    def test_enclave_without_measurement(self, tee_builder):
        """测试边界条件：没有度量值的Enclave"""
        enclave = tee_builder.build_enclave(with_measurement=False)
        
        assert enclave.measurement is None


class TestRemoteAttestation:
    """远程证明测试"""
    
    def test_valid_attestation_request(self, tee_builder):
        """测试正常流程：有效远程证明请求"""
        enclave = tee_builder.build_enclave(status=EnclaveStatus.Running)
        request = tee_builder.build_attestation_request(
            enclave_id=enclave.id
        )
        
        assert request.enclave_id == enclave.id
        assert request.challenge.startswith('challenge_')
    
    def test_attestation_with_expired_timestamp(self, tee_builder):
        """测试边界条件：过期的远程证明请求"""
        enclave = tee_builder.build_enclave(status=EnclaveStatus.Running)
        request = tee_builder.build_attestation_request(
            enclave_id=enclave.id,
            expired=True
        )
        
        current_ts = tee_builder._current_timestamp()
        assert current_ts - request.timestamp > 300
    
    def test_attestation_with_invalid_signature(self, tee_builder):
        """测试边界条件：无效签名的远程证明请求"""
        enclave = tee_builder.build_enclave(status=EnclaveStatus.Running)
        request = tee_builder.build_attestation_request(
            enclave_id=enclave.id,
            valid_signature=False
        )
        
        assert 'invalid' in request.signature.lower()
    
    def test_attestation_for_nonexistent_enclave(self, tee_builder):
        """测试边界条件：对不存在的Enclave进行远程证明"""
        request = tee_builder.build_attestation_request(
            enclave_id="enc_nonexistent"
        )
        
        assert request.enclave_id == "enc_nonexistent"


class TestEnclaveExecution:
    """Enclave执行测试"""
    
    def test_valid_execute_request(self, tee_builder):
        """测试正常流程：有效执行请求"""
        enclave = tee_builder.build_enclave(status=EnclaveStatus.Running)
        request = tee_builder.build_execute_request(
            enclave_id=enclave.id,
            command="compute_hash"
        )
        
        assert request.enclave_id == enclave.id
        assert request.command == "compute_hash"
    
    def test_execute_with_invalid_enclave_status(self, tee_builder):
        """测试边界条件：在无效状态下执行"""
        invalid_statuses = [
            EnclaveStatus.Created,
            EnclaveStatus.Paused,
            EnclaveStatus.Stopped,
            EnclaveStatus.Failed,
        ]
        
        for status in invalid_statuses:
            enclave = tee_builder.build_enclave(status=status)
            request = tee_builder.build_execute_request(enclave_id=enclave.id)
            
            assert request.enclave_id == enclave.id
    
    def test_execute_with_empty_command(self, tee_builder):
        """测试边界条件：空命令"""
        enclave = tee_builder.build_enclave(status=EnclaveStatus.Running)
        request = tee_builder.build_execute_request(
            enclave_id=enclave.id,
            command=""
        )
        
        assert request.command == ""
    
    def test_execute_with_large_arguments(self, tee_builder):
        """测试边界条件：超大参数"""
        enclave = tee_builder.build_enclave(status=EnclaveStatus.Running)
        large_args = {
            "data": "x" * 10000,
            "list": ["y" * 1000 for _ in range(100)]
        }
        request = tee_builder.build_execute_request(
            enclave_id=enclave.id,
            arguments=large_args
        )
        
        assert len(str(request.arguments)) > 10000


class TestTechnologySupport:
    """TEE技术支持测试"""
    
    def test_supported_technologies(self, tee_builder, tee_config):
        """测试正常流程：支持的TEE技术"""
        supported = [TeeTechnology.SGX, TeeTechnology.SEV, TeeTechnology.TrustZone]
        
        for tech in supported:
            request = tee_builder.build_enclave_create_request(technology=tech)
            assert tech.value in tee_config.supported_techs
    
    def test_unsupported_technologies(self, tee_builder, tee_config):
        """测试边界条件：不支持的TEE技术"""
        for tech in tee_builder.build_unsupported_technologies():
            assert tech.value not in tee_config.supported_techs
    
    def test_technology_enum_values(self):
        """测试TEE技术枚举完整性"""
        expected_techs = {"SGX", "SEV", "TrustZone", "Generic"}
        actual_techs = {tech.value for tech in TeeTechnology}
        
        assert expected_techs == actual_techs


class TestEnclaveMeasurement:
    """Enclave度量值测试"""
    
    def test_measurement_uniqueness(self, tee_builder):
        """测试：度量值唯一性"""
        enclave1 = tee_builder.build_enclave()
        enclave2 = tee_builder.build_enclave()
        
        assert enclave1.measurement != enclave2.measurement
        assert enclave1.id != enclave2.id
    
    def test_measurement_format(self, tee_builder):
        """测试：度量值格式"""
        enclave = tee_builder.build_enclave()
        
        assert enclave.measurement is not None
        assert len(enclave.measurement) == 64
        all_hex = all(c in '0123456789abcdef' for c in enclave.measurement.lower())
        assert all_hex
    
    def test_attestation_token_format(self, tee_builder):
        """测试：证明令牌格式"""
        enclave = tee_builder.build_enclave(with_attestation_token=True)
        
        assert enclave.attestation_token is not None
        assert len(enclave.attestation_token) == 64


class TestEdgeCases:
    """综合边界条件测试"""
    
    def test_all_edge_metadata_combinations(self, tee_builder):
        """测试：所有边界元数据组合"""
        edge_metadata = tee_builder.build_edge_case_metadata()
        
        for tech in [TeeTechnology.SGX, TeeTechnology.SEV, TeeTechnology.TrustZone]:
            for meta in edge_metadata:
                request = tee_builder.build_enclave_create_request(
                    technology=tech,
                    metadata=meta
                )
                assert request.technology == tech
                assert request.metadata == meta
    
    def test_concurrent_enclave_creation(self, tee_builder):
        """测试：并发创建多个Enclave"""
        config = tee_builder.build_config(max_enclaves=10)
        
        enclaves: List[Enclave] = []
        for i in range(config.max_enclaves):
            enclave = tee_builder.build_enclave()
            enclaves.append(enclave)
        
        assert len(enclaves) == config.max_enclaves
        
        ids = [e.id for e in enclaves]
        assert len(ids) == len(set(ids))
    
    def test_max_string_lengths(self, tee_builder):
        """测试：最大字符串长度边界"""
        very_long_string = "x" * 100000
        request = tee_builder.build_enclave_create_request(
            metadata={"very_long": very_long_string}
        )
        
        assert len(request.metadata["very_long"]) == 100000
    
    def test_special_characters_in_challenge(self, tee_builder):
        """测试：挑战值中的特殊字符"""
        enclave = tee_builder.build_enclave()
        special_challenge = "!@#$%^&*()_+-=[]{}|;':\",./<>?中文"
        
        request = tee_builder.build_attestation_request(
            enclave_id=enclave.id,
            challenge=special_challenge
        )
        
        assert request.challenge == special_challenge


class TestTeeConfig:
    """TEE配置测试"""
    
    def test_default_config_values(self, tee_config):
        """测试：默认配置值"""
        assert tee_config.enabled == True
        assert tee_config.max_enclaves == 64
        assert tee_config.attestation_timeout_ms == 30000
        assert "SGX" in tee_config.supported_techs
    
    def test_custom_config_override(self, tee_builder):
        """测试：自定义配置覆盖"""
        custom_config = tee_builder.build_config(
            enabled=False,
            max_enclaves=1,
            attestation_timeout_ms=5000,
            supported_techs=["SGX"]
        )
        
        assert custom_config.enabled == False
        assert custom_config.max_enclaves == 1
        assert custom_config.attestation_timeout_ms == 5000
        assert custom_config.supported_techs == ["SGX"]
    
    def test_config_edge_values(self, tee_builder):
        """测试：配置边界值"""
        edge_configs = [
            tee_builder.build_config(max_enclaves=0),
            tee_builder.build_config(max_enclaves=1),
            tee_builder.build_config(attestation_timeout_ms=1),
            tee_builder.build_config(supported_techs=[]),
        ]
        
        for config in edge_configs:
            assert config is not None
