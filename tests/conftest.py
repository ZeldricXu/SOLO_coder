"""
测试配置和公共夹具
"""
import os
import asyncio
import socket
from typing import Dict, Any, Optional
from unittest.mock import Mock, MagicMock, patch

import pytest
import requests
from dotenv import load_dotenv
from faker import Faker

load_dotenv()

fake = Faker('zh_CN')


def get_free_port() -> int:
    """获取一个可用的端口"""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(('', 0))
        return s.getsockname()[1]


class TestConfig:
    """测试配置"""
    BASE_URL = os.getenv('TEST_BASE_URL', 'http://localhost:3000')
    API_PREFIX = '/api/v1'
    TIMEOUT = 10
    MAX_RETRIES = 3
    
    # 测试数据
    TEST_NAMESPACE = 'test-namespace'
    TEST_USER_ID = 'test-user-001'
    TEST_AGGREGATE_ID = 'test-aggregate-001'


@pytest.fixture(scope='session')
def config() -> TestConfig:
    """会话级配置夹具"""
    return TestConfig()


@pytest.fixture(scope='session')
def api_client(config: TestConfig) -> requests.Session:
    """HTTP 客户端夹具"""
    session = requests.Session()
    session.headers.update({
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        'X-Test-Source': 'pytest',
    })
    
    yield session
    
    session.close()


@pytest.fixture
def chaos_scenario_data() -> Dict[str, Any]:
    """故障场景测试数据"""
    return {
        'name': f'测试场景-{fake.uuid4()[:8]}',
        'description': fake.text(max_nb_chars=200),
        'faultType': 'network_delay',
        'targetScope': {
            'namespace': TestConfig.TEST_NAMESPACE,
            'selector': {'app': 'test-app'},
            'targetIds': [f'target-{i}' for i in range(3)],
        },
        'parameters': {
            'delayMs': 500,
            'jitterMs': 100,
            'durationSeconds': 60,
        },
        'autoRollback': True,
        'rollbackConfig': {
            'timeoutSeconds': 300,
            'maxRetries': 3,
        },
        'createdBy': TestConfig.TEST_USER_ID,
    }


@pytest.fixture
def command_data() -> Dict[str, Any]:
    """命令测试数据"""
    return {
        'commandType': 'resource.create',
        'aggregateId': TestConfig.TEST_AGGREGATE_ID,
        'payload': {
            'resourceType': 'workflow',
            'config': {'timeout': 30},
        },
        'metadata': {
            'source': 'api',
            'traceId': fake.uuid4(),
        },
        'actorId': TestConfig.TEST_USER_ID,
    }


@pytest.fixture
def audit_log_data() -> Dict[str, Any]:
    """审计日志测试数据"""
    return {
        'action': 'resource.created',
        'actorId': TestConfig.TEST_USER_ID,
        'resourceId': f'resource-{fake.uuid4()[:8]}',
        'details': {
            'oldValue': None,
            'newValue': {'status': 'created'},
            'changes': ['status'],
        },
    }


@pytest.fixture
def mock_response_factory():
    """模拟响应工厂"""
    def _factory(status_code: int, data: Any = None, error: str = None) -> Mock:
        mock_resp = Mock()
        mock_resp.status_code = status_code
        mock_resp.json.return_value = {
            'code': status_code,
            'data': data,
            'error': error,
        }
        mock_resp.ok = 200 <= status_code < 300
        return mock_resp
    return _factory


@pytest.fixture
def mock_api_client(mock_response_factory):
    """模拟 API 客户端"""
    with patch('requests.Session') as mock_session_cls:
        mock_session = MagicMock()
        mock_session_cls.return_value = mock_session
        
        # 默认成功响应
        mock_session.get.return_value = mock_response_factory(200, {'status': 'ok'})
        mock_session.post.return_value = mock_response_factory(201, {'id': 'test-id'})
        mock_session.put.return_value = mock_response_factory(200, {'status': 'updated'})
        mock_session.delete.return_value = mock_response_factory(200, {'message': 'deleted'})
        
        yield mock_session


@pytest.fixture
def event_loop():
    """创建事件循环"""
    loop = asyncio.get_event_loop_policy().new_event_loop()
    yield loop
    loop.close()
