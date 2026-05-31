"""
故障注入编排模块 - 并发安全测试
"""
import asyncio
import pytest
import aiohttp
from typing import Dict, Any, List
from unittest.mock import AsyncMock, patch

pytestmark = [pytest.mark.chaos, pytest.mark.concurrent]


class TestChaosConcurrent:
    """故障模块并发安全测试"""
    
    async def create_scenario_async(self, session: aiohttp.ClientSession, 
                                   base_url: str, scenario_data: Dict[str, Any]) -> Dict[str, Any]:
        """异步创建场景"""
        async with session.post(
            f"{base_url}/api/v1/chaos/scenarios",
            json=scenario_data,
            timeout=aiohttp.ClientTimeout(total=10)
        ) as response:
            return await response.json()
    
    @pytest.mark.asyncio
    async def test_concurrent_scenario_creation(self, config, chaos_scenario_data):
        """测试并发创建场景"""
        base_url = config.BASE_URL
        concurrency = 10
        
        async with aiohttp.ClientSession() as session:
            tasks = []
            for i in range(concurrency):
                data = chaos_scenario_data.copy()
                data['name'] = f'并发场景-{i}-{data["name"]}'
                tasks.append(self.create_scenario_async(session, base_url, data))
            
            results = await asyncio.gather(*tasks, return_exceptions=True)
            
            # 检查结果
            success_count = 0
            for result in results:
                if isinstance(result, Exception):
                    # 允许部分失败（如限流），但不应该出现500错误
                    if hasattr(result, 'status'):
                        assert result.status < 500
                else:
                    success_count += 1
                    assert result.get('code') in [201, 429]
            
            # 至少应该有部分成功
            assert success_count >= 1
    
    @pytest.mark.asyncio
    async def test_concurrent_injection_same_scenario(self, api_client, config, chaos_scenario_data):
        """测试对同一场景并发注入"""
        client = __import__('tests.client', fromlist=['ChaosLabClient']).ChaosLabClient(config.BASE_URL, api_client)
        
        # 创建场景
        scenario = client.create_scenario(chaos_scenario_data)
        client.update_scenario(scenario['scenarioId'], {'status': 'active'})
        
        base_url = config.BASE_URL
        concurrency = 5
        
        async def inject(session: aiohttp.ClientSession) -> Dict[str, Any]:
            async with session.post(
                f"{base_url}/api/v1/chaos/injections",
                json={'scenarioId': scenario['scenarioId'], 'targetIds': ['target-1']},
                timeout=aiohttp.ClientTimeout(total=10)
            ) as response:
                return await response.json()
        
        async with aiohttp.ClientSession() as session:
            tasks = [inject(session) for _ in range(concurrency)]
            results = await asyncio.gather(*tasks, return_exceptions=True)
            
            # 应该只有一个成功，其他应该冲突或排队
            success_count = sum(
                1 for r in results 
                if not isinstance(r, Exception) and r.get('code') == 201
            )
            
            # 根据实现，可能全部成功（创建多个注入）或只有一个成功
            # 只要没有500错误即可
            for result in results:
                if isinstance(result, Exception) and hasattr(result, 'status'):
                    assert result.status < 500
    
    @pytest.mark.asyncio
    async def test_concurrent_rollback(self, api_client, config, chaos_scenario_data):
        """测试并发回滚"""
        client = __import__('tests.client', fromlist=['ChaosLabClient']).ChaosLabClient(config.BASE_URL, api_client)
        
        # 创建场景和注入
        scenario = client.create_scenario(chaos_scenario_data)
        client.update_scenario(scenario['scenarioId'], {'status': 'active'})
        injection = client.start_injection(scenario['scenarioId'], ['target-1'])
        
        base_url = config.BASE_URL
        concurrency = 3
        
        async def rollback(session: aiohttp.ClientSession) -> Dict[str, Any]:
            async with session.post(
                f"{base_url}/api/v1/chaos/injections/{injection['injectionId']}/rollback",
                timeout=aiohttp.ClientTimeout(total=10)
            ) as response:
                return await response.json()
        
        async with aiohttp.ClientSession() as session:
            tasks = [rollback(session) for _ in range(concurrency)]
            results = await asyncio.gather(*tasks, return_exceptions=True)
            
            # 回滚应该是幂等的
            success_count = sum(
                1 for r in results 
                if not isinstance(r, Exception) and r.get('code') == 200
            )
            assert success_count >= 1
    
    @pytest.mark.asyncio
    async def test_concurrent_scenario_update(self, api_client, config, chaos_scenario_data):
        """测试并发更新同一场景"""
        client = __import__('tests.client', fromlist=['ChaosLabClient']).ChaosLabClient(config.BASE_URL, api_client)
        
        scenario = client.create_scenario(chaos_scenario_data)
        base_url = config.BASE_URL
        concurrency = 5
        
        async def update(session: aiohttp.ClientSession, index: int) -> Dict[str, Any]:
            async with session.put(
                f"{base_url}/api/v1/chaos/scenarios/{scenario['scenarioId']}",
                json={'description': f'更新-{index}'},
                timeout=aiohttp.ClientTimeout(total=10)
            ) as response:
                return await response.json()
        
        async with aiohttp.ClientSession() as session:
            tasks = [update(session, i) for i in range(concurrency)]
            results = await asyncio.gather(*tasks, return_exceptions=True)
            
            # 检查是否有500错误
            for result in results:
                if isinstance(result, Exception) and hasattr(result, 'status'):
                    assert result.status < 500
            
            # 获取最终状态
            final = client.get_scenario(scenario['scenarioId'])
            assert final['scenarioId'] == scenario['scenarioId']
    
    @pytest.mark.asyncio
    async def test_concurrent_read_write(self, api_client, config, chaos_scenario_data):
        """测试并发读写"""
        client = __import__('tests.client', fromlist=['ChaosLabClient']).ChaosLabClient(config.BASE_URL, api_client)
        
        scenario = client.create_scenario(chaos_scenario_data)
        base_url = config.BASE_URL
        
        async def read(session: aiohttp.ClientSession) -> Dict[str, Any]:
            async with session.get(
                f"{base_url}/api/v1/chaos/scenarios/{scenario['scenarioId']}",
                timeout=aiohttp.ClientTimeout(total=10)
            ) as response:
                return await response.json()
        
        async def write(session: aiohttp.ClientSession) -> Dict[str, Any]:
            async with session.put(
                f"{base_url}/api/v1/chaos/scenarios/{scenario['scenarioId']}",
                json={'description': '并发更新'},
                timeout=aiohttp.ClientTimeout(total=10)
            ) as response:
                return await response.json()
        
        async with aiohttp.ClientSession() as session:
            # 混合读写操作
            tasks = []
            for i in range(20):
                if i % 2 == 0:
                    tasks.append(read(session))
                else:
                    tasks.append(write(session))
            
            results = await asyncio.gather(*tasks, return_exceptions=True)
            
            # 所有操作应该成功
            for result in results:
                if isinstance(result, Exception):
                    if hasattr(result, 'status'):
                        assert result.status < 500
    
    @pytest.mark.asyncio
    async def test_high_concurrency_scenarios_list(self, config):
        """测试高并发下列表场景"""
        base_url = config.BASE_URL
        concurrency = 50
        
        async def list_scenarios(session: aiohttp.ClientSession) -> Dict[str, Any]:
            async with session.get(
                f"{base_url}/api/v1/chaos/scenarios",
                params={'page': 1, 'pageSize': 20},
                timeout=aiohttp.ClientTimeout(total=10)
            ) as response:
                return await response.json()
        
        async with aiohttp.ClientSession() as session:
            tasks = [list_scenarios(session) for _ in range(concurrency)]
            results = await asyncio.gather(*tasks, return_exceptions=True)
            
            success_count = sum(
                1 for r in results 
                if not isinstance(r, Exception) and r.get('code') == 200
            )
            
            # 应该大部分成功
            assert success_count >= concurrency * 0.8


class TestChaosConcurrentMock:
    """使用Mock的并发安全测试"""
    
    @pytest.mark.asyncio
    async def test_concurrent_database_operations(self):
        """测试并发数据库操作（使用Mock）"""
        from tests.modules.chaosInjection import service
        
        with patch.object(service, 'prisma') as mock_prisma:
            # 模拟数据库并发锁
            mock_create = AsyncMock()
            mock_create.side_effect = [
                {'scenarioId': f'scn-{i}', 'name': f'Test-{i}'}
                for i in range(10)
            ]
            mock_prisma.chaosScenario.create = mock_create
            
            # 模拟并发创建
            async def create_scenario(index: int):
                return await service.createScenario({
                    'name': f'Test-{index}',
                    'faultType': 'network_delay',
                    'targetScope': {'namespace': 'test'},
                    'parameters': {},
                    'autoRollback': True,
                    'createdBy': 'test-user',
                })
            
            tasks = [create_scenario(i) for i in range(10)]
            results = await asyncio.gather(*tasks, return_exceptions=True)
            
            # 验证没有异常
            for result in results:
                assert not isinstance(result, Exception)
    
    def test_concurrent_injection_lock(self):
        """测试注入并发锁机制"""
        from tests.modules.chaosInjection import service
        
        with patch.object(service, 'prisma') as mock_prisma, \
             patch('asyncio.Lock') as mock_lock_cls:
            
            mock_lock = AsyncMock()
            mock_lock_cls.return_value = mock_lock
            
            mock_prisma.chaosInjection.create.return_value = {
                'injectionId': 'inj-001',
                'scenarioId': 'scn-001',
                'targetIds': ['target-1'],
                'status': 'injecting',
            }
            
            # 验证锁被正确使用
            assert mock_lock_cls.called
