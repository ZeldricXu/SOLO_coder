"""
故障注入编排模块 - 边界值测试
"""
import pytest
import requests
from typing import Dict, Any
import uuid

from tests.client import ChaosLabClient
from tests.conftest import TestConfig


pytestmark = [pytest.mark.chaos, pytest.mark.boundary]


class TestChaosScenarioBoundaries:
    """故障场景边界值测试"""
    
    def test_scenario_name_empty_string(self, api_client: requests.Session,
                                       config: TestConfig,
                                       chaos_scenario_data: Dict[str, Any]):
        """测试场景名称为空字符串 - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        chaos_scenario_data['name'] = ''
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.create_scenario(chaos_scenario_data)
        
        assert exc_info.value.response.status_code in [400, 422]
    
    def test_scenario_name_too_long(self, api_client: requests.Session,
                                    config: TestConfig,
                                    chaos_scenario_data: Dict[str, Any]):
        """测试场景名称超过最大长度 - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        chaos_scenario_data['name'] = 'a' * 1000  # 超过最大长度
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.create_scenario(chaos_scenario_data)
        
        assert exc_info.value.response.status_code in [400, 422]
    
    def test_scenario_invalid_fault_type(self, api_client: requests.Session,
                                         config: TestConfig,
                                         chaos_scenario_data: Dict[str, Any]):
        """测试无效的故障类型 - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        chaos_scenario_data['faultType'] = 'invalid_fault_type'
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.create_scenario(chaos_scenario_data)
        
        assert exc_info.value.response.status_code in [400, 422]
    
    def test_scenario_empty_target_scope(self, api_client: requests.Session,
                                         config: TestConfig,
                                         chaos_scenario_data: Dict[str, Any]):
        """测试空的目标范围 - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        chaos_scenario_data['targetScope'] = {}
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.create_scenario(chaos_scenario_data)
        
        assert exc_info.value.response.status_code in [400, 422]
    
    def test_scenario_missing_namespace(self, api_client: requests.Session,
                                        config: TestConfig,
                                        chaos_scenario_data: Dict[str, Any]):
        """测试目标范围缺少命名空间 - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        chaos_scenario_data['targetScope'] = {'selector': {'app': 'test'}}
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.create_scenario(chaos_scenario_data)
        
        assert exc_info.value.response.status_code in [400, 422]
    
    def test_scenario_negative_rollback_timeout(self, api_client: requests.Session,
                                                config: TestConfig,
                                                chaos_scenario_data: Dict[str, Any]):
        """测试回滚超时为负数 - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        chaos_scenario_data['rollbackConfig'] = {
            'timeoutSeconds': -100,
            'maxRetries': 3,
        }
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.create_scenario(chaos_scenario_data)
        
        assert exc_info.value.response.status_code in [400, 422]
    
    def test_scenario_zero_max_retries(self, api_client: requests.Session,
                                       config: TestConfig,
                                       chaos_scenario_data: Dict[str, Any]):
        """测试最大重试次数为0 - 应该成功（允许不重试）"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        chaos_scenario_data['rollbackConfig'] = {
            'timeoutSeconds': 300,
            'maxRetries': 0,
        }
        
        result = client.create_scenario(chaos_scenario_data)
        assert result['rollbackConfig']['maxRetries'] == 0
    
    def test_scenario_maximum_parameters(self, api_client: requests.Session,
                                         config: TestConfig,
                                         chaos_scenario_data: Dict[str, Any]):
        """测试最大参数值 - 应该成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        chaos_scenario_data['parameters'] = {
            'delayMs': 2**31 - 1,  # 最大32位整数
            'jitterMs': 1000,
            'packetLossPercent': 100,
            'cpuLoadPercent': 100,
        }
        
        result = client.create_scenario(chaos_scenario_data)
        assert result['parameters']['delayMs'] == 2**31 - 1
    
    def test_scenario_boundary_pagination(self, api_client: requests.Session,
                                          config: TestConfig,
                                          chaos_scenario_data: Dict[str, Any]):
        """测试分页边界值"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        # 测试 page=0 - 应该返回第一页
        result = client.list_scenarios(page=0, page_size=20)
        assert result['page'] in [0, 1]  # 取决于实现是否自动修正
        
        # 测试 page_size=1
        result = client.list_scenarios(page=1, page_size=1)
        assert len(result['items']) <= 1
        
        # 测试 page_size=0 - 应该使用默认值或报错
        try:
            result = client.list_scenarios(page=1, page_size=0)
            assert len(result['items']) >= 0
        except requests.HTTPError as e:
            assert e.response.status_code in [400, 422]
        
        # 测试超大 page_size
        result = client.list_scenarios(page=1, page_size=1000)
        assert len(result['items']) <= 1000
    
    def test_scenario_special_characters_name(self, api_client: requests.Session,
                                               config: TestConfig,
                                               chaos_scenario_data: Dict[str, Any]):
        """测试场景名称包含特殊字符"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        special_names = [
            '测试场景-中文-😀',
            'scenario_<script>alert(1)</script>',
            'scenario with spaces',
            'scenario/with/slashes',
            'scenario\\with\\backslashes',
            'scenario--emoji-🔥-🚀',
        ]
        
        for name in special_names:
            chaos_scenario_data['name'] = name
            # 可能成功或失败，取决于是否有输入过滤
            try:
                result = client.create_scenario(chaos_scenario_data)
                assert result['name'] == name  # 如果成功，名称应该被正确存储
            except requests.HTTPError as e:
                # 如果失败，应该是400/422，而不是500
                assert e.response.status_code in [400, 422]


class TestChaosInjectionBoundaries:
    """故障注入边界值测试"""
    
    def test_injection_empty_target_ids(self, api_client: requests.Session,
                                        config: TestConfig,
                                        chaos_scenario_data: Dict[str, Any]):
        """测试空的目标ID列表 - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        scenario = client.create_scenario(chaos_scenario_data)
        client.update_scenario(scenario['scenarioId'], {'status': 'active'})
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.start_injection(scenario['scenarioId'], target_ids=[])
        
        assert exc_info.value.response.status_code in [400, 422]
    
    def test_injection_nonexistent_scenario(self, api_client: requests.Session,
                                            config: TestConfig):
        """测试不存在的场景ID - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.start_injection('nonexistent-scenario-id', ['target-1'])
        
        assert exc_info.value.response.status_code in [400, 404]
    
    def test_injection_inactive_scenario(self, api_client: requests.Session,
                                         config: TestConfig,
                                         chaos_scenario_data: Dict[str, Any]):
        """测试非激活状态的场景 - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        scenario = client.create_scenario(chaos_scenario_data)
        # 场景默认为 draft 状态，不激活
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.start_injection(scenario['scenarioId'], ['target-1'])
        
        assert exc_info.value.response.status_code in [400, 409, 422]
    
    def test_injection_already_completed_rollback(self, api_client: requests.Session,
                                                  config: TestConfig,
                                                  chaos_scenario_data: Dict[str, Any]):
        """测试回滚已完成的注入 - 应该失败或幂等"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        scenario = client.create_scenario(chaos_scenario_data)
        client.update_scenario(scenario['scenarioId'], {'status': 'active'})
        
        injection = client.start_injection(scenario['scenarioId'], ['target-1'])
        client.rollback_injection(injection['injectionId'])
        
        # 第二次回滚可能失败或幂等返回成功
        try:
            client.rollback_injection(injection['injectionId'])
        except requests.HTTPError as e:
            assert e.response.status_code in [400, 409, 422]
    
    def test_injection_max_target_ids(self, api_client: requests.Session,
                                       config: TestConfig,
                                       chaos_scenario_data: Dict[str, Any]):
        """测试大量目标ID"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        scenario = client.create_scenario(chaos_scenario_data)
        client.update_scenario(scenario['scenarioId'], {'status': 'active'})
        
        # 生成100个目标ID
        target_ids = [f'target-{i}-{uuid.uuid4()}' for i in range(100)]
        
        try:
            injection = client.start_injection(scenario['scenarioId'], target_ids)
            assert len(injection['targetIds']) == 100
        except requests.HTTPError as e:
            # 如果有数量限制，应该返回400/422
            assert e.response.status_code in [400, 422]
    
    def test_injection_nonexistent_injection_get(self, api_client: requests.Session,
                                                  config: TestConfig):
        """测试获取不存在的注入 - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.get_injection('nonexistent-injection-id')
        
        assert exc_info.value.response.status_code in [400, 404]
    
    def test_injection_nonexistent_injection_rollback(self, api_client: requests.Session,
                                                       config: TestConfig):
        """测试回滚不存在的注入 - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.rollback_injection('nonexistent-injection-id')
        
        assert exc_info.value.response.status_code in [400, 404]


class TestChaosEdgeCases:
    """故障模块极端情况测试"""
    
    def test_scenario_update_idempotent(self, api_client: requests.Session,
                                        config: TestConfig,
                                        chaos_scenario_data: Dict[str, Any]):
        """测试场景更新的幂等性"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        scenario = client.create_scenario(chaos_scenario_data)
        
        # 多次相同的更新
        for i in range(3):
            updated = client.update_scenario(
                scenario['scenarioId'],
                {'description': '相同的描述'}
            )
            assert updated['description'] == '相同的描述'
    
    def test_scenario_delete_nonexistent(self, api_client: requests.Session,
                                         config: TestConfig):
        """测试删除不存在的场景 - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.delete_scenario('nonexistent-scenario-id')
        
        assert exc_info.value.response.status_code in [400, 404]
    
    def test_scenario_delete_twice(self, api_client: requests.Session,
                                   config: TestConfig,
                                   chaos_scenario_data: Dict[str, Any]):
        """测试重复删除场景 - 第二次应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        scenario = client.create_scenario(chaos_scenario_data)
        client.delete_scenario(scenario['scenarioId'])
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.delete_scenario(scenario['scenarioId'])
        
        assert exc_info.value.response.status_code in [400, 404]
    
    def test_scenario_fault_types_all(self, api_client: requests.Session,
                                      config: TestConfig,
                                      chaos_scenario_data: Dict[str, Any]):
        """测试所有支持的故障类型"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        fault_types = [
            'network_delay',
            'packet_loss',
            'cpu_stress',
            'memory_stress',
            'disk_io',
            'service_kill',
            'dns_poison',
        ]
        
        for fault_type in fault_types:
            chaos_scenario_data['faultType'] = fault_type
            try:
                result = client.create_scenario(chaos_scenario_data)
                assert result['faultType'] == fault_type
            except requests.HTTPError as e:
                # 某些故障类型可能需要特定参数
                assert e.response.status_code in [400, 422]
