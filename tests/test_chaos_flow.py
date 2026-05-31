"""
故障注入编排模块 - 正常业务流程测试
"""
import pytest
import requests
from typing import Dict, Any

from tests.client import ChaosLabClient
from tests.conftest import TestConfig


pytestmark = [pytest.mark.chaos, pytest.mark.integration]


class TestChaosScenarioFlow:
    """故障场景正常流程测试"""
    
    def test_create_scenario_success(self, api_client: requests.Session, 
                                   config: TestConfig,
                                   chaos_scenario_data: Dict[str, Any]):
        """测试创建故障场景成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        result = client.create_scenario(chaos_scenario_data)
        
        assert result['scenarioId'] is not None
        assert result['name'] == chaos_scenario_data['name']
        assert result['faultType'] == chaos_scenario_data['faultType']
        assert result['status'] == 'draft'
        assert result['createdBy'] == chaos_scenario_data['createdBy']
    
    def test_get_scenario_success(self, api_client: requests.Session,
                                 config: TestConfig,
                                 chaos_scenario_data: Dict[str, Any]):
        """测试获取故障场景成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        created = client.create_scenario(chaos_scenario_data)
        retrieved = client.get_scenario(created['scenarioId'])
        
        assert retrieved['scenarioId'] == created['scenarioId']
        assert retrieved['name'] == created['name']
        assert retrieved['targetScope'] == chaos_scenario_data['targetScope']
    
    def test_list_scenarios_success(self, api_client: requests.Session,
                                   config: TestConfig,
                                   chaos_scenario_data: Dict[str, Any]):
        """测试列出故障场景成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        # 创建多个场景
        for i in range(3):
            data = chaos_scenario_data.copy()
            data['name'] = f'场景-{i}-{data["name"]}'
            client.create_scenario(data)
        
        result = client.list_scenarios(page=1, page_size=10)
        
        assert result['total'] >= 3
        assert len(result['items']) <= 10
        assert result['page'] == 1
        assert result['totalPages'] >= 1
    
    def test_update_scenario_success(self, api_client: requests.Session,
                                    config: TestConfig,
                                    chaos_scenario_data: Dict[str, Any]):
        """测试更新故障场景成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        created = client.create_scenario(chaos_scenario_data)
        updated_name = f'更新-{created["name"]}'
        
        updated = client.update_scenario(
            created['scenarioId'],
            {'name': updated_name, 'description': '更新后的描述'}
        )
        
        assert updated['scenarioId'] == created['scenarioId']
        assert updated['name'] == updated_name
        assert updated['description'] == '更新后的描述'
    
    def test_delete_scenario_success(self, api_client: requests.Session,
                                    config: TestConfig,
                                    chaos_scenario_data: Dict[str, Any]):
        """测试删除故障场景成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        created = client.create_scenario(chaos_scenario_data)
        result = client.delete_scenario(created['scenarioId'])
        
        assert result['code'] == 200
        assert 'deleted' in result['message'].lower()


class TestChaosInjectionFlow:
    """故障注入正常流程测试"""
    
    def test_start_injection_success(self, api_client: requests.Session,
                                    config: TestConfig,
                                    chaos_scenario_data: Dict[str, Any]):
        """测试开始故障注入成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        # 先创建场景并激活
        scenario = client.create_scenario(chaos_scenario_data)
        client.update_scenario(scenario['scenarioId'], {'status': 'active'})
        
        # 开始注入
        target_ids = chaos_scenario_data['targetScope']['targetIds']
        injection = client.start_injection(scenario['scenarioId'], target_ids)
        
        assert injection['injectionId'] is not None
        assert injection['scenarioId'] == scenario['scenarioId']
        assert injection['status'] in ['injecting', 'pending']
        assert injection['targetIds'] == target_ids
    
    def test_get_injection_success(self, api_client: requests.Session,
                                  config: TestConfig,
                                  chaos_scenario_data: Dict[str, Any]):
        """测试获取注入状态成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        scenario = client.create_scenario(chaos_scenario_data)
        client.update_scenario(scenario['scenarioId'], {'status': 'active'})
        
        target_ids = chaos_scenario_data['targetScope']['targetIds']
        injection = client.start_injection(scenario['scenarioId'], target_ids)
        
        retrieved = client.get_injection(injection['injectionId'])
        assert retrieved['injectionId'] == injection['injectionId']
        assert retrieved['scenarioId'] == scenario['scenarioId']
    
    def test_rollback_injection_success(self, api_client: requests.Session,
                                       config: TestConfig,
                                       chaos_scenario_data: Dict[str, Any]):
        """测试回滚故障注入成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        scenario = client.create_scenario(chaos_scenario_data)
        client.update_scenario(scenario['scenarioId'], {'status': 'active'})
        
        target_ids = chaos_scenario_data['targetScope']['targetIds']
        injection = client.start_injection(scenario['scenarioId'], target_ids)
        
        rollback_result = client.rollback_injection(injection['injectionId'])
        
        assert rollback_result['injectionId'] == injection['injectionId']
        assert rollback_result['status'] in ['rolling_back', 'completed']
        assert rollback_result['rollbackAt'] is not None


class TestChaosScenarioLifecycle:
    """故障场景完整生命周期测试"""
    
    def test_full_scenario_lifecycle(self, api_client: requests.Session,
                                    config: TestConfig,
                                    chaos_scenario_data: Dict[str, Any]):
        """测试场景完整生命周期：创建→更新→激活→注入→回滚→删除"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        # 1. 创建场景
        scenario = client.create_scenario(chaos_scenario_data)
        assert scenario['status'] == 'draft'
        
        # 2. 更新场景参数
        updated = client.update_scenario(
            scenario['scenarioId'],
            {
                'parameters': {'delayMs': 1000, 'durationSeconds': 120},
                'status': 'active'
            }
        )
        assert updated['status'] == 'active'
        assert updated['parameters']['delayMs'] == 1000
        
        # 3. 开始注入
        target_ids = ['target-1', 'target-2']
        injection = client.start_injection(scenario['scenarioId'], target_ids)
        assert injection['status'] in ['injecting', 'active']
        
        # 4. 检查注入状态
        injection_status = client.get_injection(injection['injectionId'])
        assert injection_status['startedAt'] is not None
        
        # 5. 回滚注入
        rollback = client.rollback_injection(injection['injectionId'])
        assert rollback['status'] in ['rolling_back', 'completed']
        
        # 6. 删除场景
        client.delete_scenario(scenario['scenarioId'])
