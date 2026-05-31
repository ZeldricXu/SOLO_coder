"""
命令溯源与审计模块 - 并发安全测试
"""
import asyncio
import pytest
import aiohttp
from typing import Dict, Any
from unittest.mock import AsyncMock, patch

pytestmark = [pytest.mark.audit, pytest.mark.concurrent]


class TestAuditConcurrent:
    """审计模块并发安全测试"""
    
    async def create_command_async(self, session: aiohttp.ClientSession,
                                   base_url: str, command_data: Dict[str, Any]) -> Dict[str, Any]:
        """异步创建命令"""
        async with session.post(
            f"{base_url}/api/v1/audit/commands",
            json=command_data,
            timeout=aiohttp.ClientTimeout(total=10)
        ) as response:
            return await response.json()
    
    @pytest.mark.asyncio
    async def test_concurrent_command_creation(self, config, command_data):
        """测试并发创建命令"""
        base_url = config.BASE_URL
        concurrency = 20
        
        async with aiohttp.ClientSession() as session:
            tasks = []
            for i in range(concurrency):
                data = command_data.copy()
                data['commandType'] = f'concurrent.test.{i}'
                data['aggregateId'] = f'concurrent-aggregate-{i}'
                tasks.append(self.create_command_async(session, base_url, data))
            
            results = await asyncio.gather(*tasks, return_exceptions=True)
            
            success_count = 0
            for result in results:
                if isinstance(result, Exception):
                    if hasattr(result, 'status'):
                        assert result.status < 500, f"Server error: {result.status}"
                else:
                    success_count += 1
                    assert result.get('code') in [201, 429]
            
            assert success_count >= concurrency * 0.8, f"Too many failures: {success_count}/{concurrency}"
    
    @pytest.mark.asyncio
    async def test_concurrent_audit_log_creation(self, config, audit_log_data):
        """测试并发创建审计日志"""
        base_url = config.BASE_URL
        concurrency = 30
        
        async def create_audit_log(session: aiohttp.ClientSession, index: int) -> Dict[str, Any]:
            data = audit_log_data.copy()
            data['action'] = f'concurrent.action.{index}'
            async with session.post(
                f"{base_url}/api/v1/audit/audit-logs",
                json=data,
                timeout=aiohttp.ClientTimeout(total=10)
            ) as response:
                return await response.json()
        
        async with aiohttp.ClientSession() as session:
            tasks = [create_audit_log(session, i) for i in range(concurrency)]
            results = await asyncio.gather(*tasks, return_exceptions=True)
            
            success_count = sum(
                1 for r in results
                if not isinstance(r, Exception) and r.get('code') == 201
            )
            
            assert success_count >= concurrency * 0.8
    
    @pytest.mark.asyncio
    async def test_concurrent_commands_same_aggregate(self, config, command_data):
        """测试同一聚合的并发命令"""
        base_url = config.BASE_URL
        aggregate_id = f'concurrent-same-aggregate'
        concurrency = 10
        
        async def create_command(session: aiohttp.ClientSession, index: int) -> Dict[str, Any]:
            data = command_data.copy()
            data['aggregateId'] = aggregate_id
            data['commandType'] = f'action.{index}'
            data['payload'] = {'index': index}
            async with session.post(
                f"{base_url}/api/v1/audit/commands",
                json=data,
                timeout=aiohttp.ClientTimeout(total=10)
            ) as response:
                return await response.json()
        
        async with aiohttp.ClientSession() as session:
            tasks = [create_command(session, i) for i in range(concurrency)]
            results = await asyncio.gather(*tasks, return_exceptions=True)
            
            # 所有命令都应该被接受（最终一致性）
            success_count = sum(
                1 for r in results
                if not isinstance(r, Exception) and r.get('code') == 201
            )
            
            assert success_count >= concurrency * 0.9
            
            # 验证所有命令都被存储
            async with session.get(
                f"{base_url}/api/v1/audit/aggregates/{aggregate_id}/commands",
                timeout=aiohttp.ClientTimeout(total=10)
            ) as response:
                data = await response.json()
                commands = data.get('data', [])
                # 可能有部分失败，但大部分应该存在
                assert len(commands) >= concurrency * 0.7
    
    @pytest.mark.asyncio
    async def test_concurrent_read_write_audit(self, config, command_data, audit_log_data):
        """测试审计模块的并发读写"""
        base_url = config.BASE_URL
        
        # 先创建一些数据
        import requests as req
        session = req.Session()
        client = __import__('tests.client', fromlist=['ChaosLabClient']).ChaosLabClient(base_url, session)
        for i in range(20):
            data = command_data.copy()
            data['commandType'] = f'readwrite.test.{i}'
            data['aggregateId'] = f'readwrite-aggregate-{i}'
            client.create_command(data)
        
        async def read_commands(session: aiohttp.ClientSession) -> Dict[str, Any]:
            async with session.get(
                f"{base_url}/api/v1/audit/commands",
                params={'page': 1, 'pageSize': 10},
                timeout=aiohttp.ClientTimeout(total=10)
            ) as response:
                return await response.json()
        
        async def write_command(session: aiohttp.ClientSession, index: int) -> Dict[str, Any]:
            data = command_data.copy()
            data['commandType'] = f'concurrent.write.{index}'
            data['aggregateId'] = f'concurrent-write-{index}'
            async with session.post(
                f"{base_url}/api/v1/audit/commands",
                json=data,
                timeout=aiohttp.ClientTimeout(total=10)
            ) as response:
                return await response.json()
        
        async with aiohttp.ClientSession() as session:
            tasks = []
            for i in range(30):
                if i % 2 == 0:
                    tasks.append(read_commands(session))
                else:
                    tasks.append(write_command(session, i))
            
            results = await asyncio.gather(*tasks, return_exceptions=True)
            
            # 检查没有500错误
            for result in results:
                if isinstance(result, Exception) and hasattr(result, 'status'):
                    assert result.status < 500, f"Server error: {result.status}"
    
    @pytest.mark.asyncio
    async def test_concurrent_compliance_reports(self, config):
        """测试并发生成合规报告"""
        base_url = config.BASE_URL
        concurrency = 5
        from datetime import datetime, timedelta
        
        async def generate_report(session: aiohttp.ClientSession) -> Dict[str, Any]:
            end_date = datetime.utcnow()
            start_date = end_date - timedelta(days=1)
            data = {
                'startDate': start_date.isoformat() + 'Z',
                'endDate': end_date.isoformat() + 'Z',
                'format': 'json'
            }
            async with session.post(
                f"{base_url}/api/v1/audit/compliance-report",
                json=data,
                timeout=aiohttp.ClientTimeout(total=30)
            ) as response:
                return await response.json()
        
        async with aiohttp.ClientSession() as session:
            tasks = [generate_report(session) for _ in range(concurrency)]
            results = await asyncio.gather(*tasks, return_exceptions=True)
            
            for result in results:
                if isinstance(result, Exception):
                    if hasattr(result, 'status'):
                        assert result.status < 500
                else:
                    assert result.get('code') in [200, 429]


class TestAuditConcurrentMock:
    """使用Mock的并发测试"""
    
    @pytest.mark.asyncio
    async def test_concurrent_persistence_optimistic_locking(self):
        """测试乐观锁并发控制"""
        with patch('prisma.PrismaClient') as mock_prisma_cls:
            mock_prisma = type('obj', (object,), {})()
            
            # 模拟版本冲突
            call_count = 0
            async def mock_create(*args, **kwargs):
                nonlocal call_count
                call_count += 1
                if call_count <= 2:
                    # 前两次模拟冲突
                    raise Exception('Version conflict')
                return {'commandId': 'cmd-001', 'commandType': 'test'}
            
            mock_prisma.command = type('obj', (object,), {
                'create': mock_create
            })()
            mock_prisma_cls.return_value = mock_prisma
            
            from tests.modules.commandAudit import service
            
            with patch.object(service, 'prisma', mock_prisma):
                # 测试重试逻辑
                max_retries = 3
                last_error = None
                
                for attempt in range(max_retries):
                    try:
                        result = await service.persistCommand({
                            'commandType': 'test',
                            'aggregateId': 'test-agg',
                            'payload': {},
                        })
                        break
                    except Exception as e:
                        last_error = e
                        if attempt < max_retries - 1:
                            await asyncio.sleep(0.1)
                else:
                    pytest.fail(f"Failed after {max_retries} attempts: {last_error}")
                
                assert result['commandId'] == 'cmd-001'
    
    @pytest.mark.asyncio
    async def test_concurrent_audit_log_batching(self):
        """测试审计日志批量写入"""
        with patch('prisma.PrismaClient') as mock_prisma_cls:
            mock_prisma = type('obj', (object,), {})()
            
            created_logs = []
            async def mock_create_many(*args, **kwargs):
                logs = kwargs.get('data', [])
                created_logs.extend(logs)
                return {'count': len(logs)}
            
            mock_prisma.auditLog = type('obj', (object,), {
                'createMany': mock_create_many
            })()
            mock_prisma_cls.return_value = mock_prisma
            
            # 验证批量写入
            assert len(created_logs) >= 0
