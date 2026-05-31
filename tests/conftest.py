"""
pytest 配置文件
提供测试夹具和通用工具
"""
import os
import sys
import pytest
from unittest.mock import MagicMock, patch
from typing import Dict, Any, Generator
from dotenv import load_dotenv

load_dotenv('.env.test')

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


@pytest.fixture(scope="session")
def api_base_url() -> str:
    """API基础URL夹具"""
    return os.getenv('API_BASE_URL', 'http://localhost:8080/api/v1')


@pytest.fixture(scope="session")
def enable_mock() -> bool:
    """是否启用mock"""
    return os.getenv('ENABLE_MOCK', 'true').lower() == 'true'


@pytest.fixture
def mock_http_client() -> Generator[MagicMock, None, None]:
    """Mock HTTP客户端"""
    with patch('requests.Session') as mock:
        yield mock


@pytest.fixture
def mock_aiohttp_client() -> Generator[MagicMock, None, None]:
    """Mock异步HTTP客户端"""
    with patch('aiohttp.ClientSession') as mock:
        yield mock


@pytest.fixture
def privacy_test_data():
    """差分隐私测试数据夹具"""
    from builders.privacy_builder import PrivacyTestDataBuilder
    return PrivacyTestDataBuilder()


@pytest.fixture
def notification_test_data():
    """通知测试数据夹具"""
    from builders.notification_builder import NotificationTestDataBuilder
    return NotificationTestDataBuilder()


@pytest.fixture
def core_test_data():
    """核心处理测试数据夹具"""
    from builders.core_builder import CoreProcessingTestDataBuilder
    return CoreProcessingTestDataBuilder()


@pytest.fixture
def mock_privacy_service():
    """Mock差分隐私服务"""
    with patch('api_clients.PrivacyApiClient') as mock:
        instance = mock.return_value
        
        instance.apply_privacy.return_value = {
            'code': 200,
            'message': 'success',
            'data': {'count': 42, '_noise_added': True}
        }
        
        instance.get_budget.return_value = {
            'code': 200,
            'message': 'success',
            'data': {
                'userId': 'test_user',
                'epsilonRemaining': 9.9,
                'deltaRemaining': 0.00099,
                'totalQueries': 1
            }
        }
        
        instance.reset_budget.return_value = {
            'code': 200,
            'message': 'success',
            'data': {
                'userId': 'test_user',
                'epsilonRemaining': 10.0,
                'deltaRemaining': 0.001,
                'totalQueries': 0
            }
        }
        
        yield instance


@pytest.fixture
def mock_notification_service():
    """Mock通知服务"""
    with patch('api_clients.NotificationApiClient') as mock:
        instance = mock.return_value
        
        instance.create_notification.return_value = {
            'code': 200,
            'message': 'success',
            'data': {
                'notificationId': 'notif_test_001',
                'type': 'EMAIL',
                'recipient': 'test@example.com',
                'status': 'PENDING'
            }
        }
        
        instance.get_status.return_value = {
            'code': 200,
            'message': 'success',
            'data': {
                'notificationId': 'notif_test_001',
                'status': 'DELIVERED',
                'retryCount': 0
            }
        }
        
        instance.retry_notification.return_value = {
            'code': 200,
            'message': 'success',
            'data': None
        }
        
        yield instance


@pytest.fixture
def mock_core_service():
    """Mock核心处理服务"""
    with patch('api_clients.CoreApiClient') as mock:
        instance = mock.return_value
        
        instance.create_resource.return_value = {
            'code': 201,
            'message': 'success',
            'data': {
                'id': 'rsc_test_001',
                'status': 'provisioning'
            }
        }
        
        instance.get_resource_status.return_value = {
            'code': 200,
            'message': 'success',
            'data': {
                'id': 'rsc_test_001',
                'status': 'completed',
                'progress': 1.0
            }
        }
        
        instance.execute_handler.return_value = {
            'code': 200,
            'message': 'success',
            'data': {
                'success': True,
                'processed_at': '2024-01-01T00:00:00Z'
            }
        }
        
        instance.batch_operation.return_value = {
            'code': 200,
            'message': 'success',
            'data': {
                'batch_id': 'batch_test_001',
                'results': []
            }
        }
        
        yield instance


@pytest.fixture
def sample_privacy_request() -> Dict[str, Any]:
    """示例差分隐私请求"""
    return {
        'userId': 'test_user_001',
        'queryResult': {
            'count': 1000,
            'sum': 50000.0,
            'avg': 50.0
        },
        'sensitivity': 1.0
    }


@pytest.fixture
def sample_notification_request() -> Dict[str, Any]:
    """示例通知请求"""
    return {
        'type': 'EMAIL',
        'recipient': 'test@example.com',
        'content': '这是一条测试通知'
    }


@pytest.fixture
def sample_execute_request() -> Dict[str, Any]:
    """示例执行请求"""
    return {
        'traceId': 'trace_test_001',
        'params': {
            'required_field': 'value',
            'optional_field': 'optional'
        },
        'namespace': 'test',
        'payload': {
            'action': 'process',
            'data': {'key': 'value'}
        }
    }


@pytest.fixture
def sample_resource_request() -> Dict[str, Any]:
    """示例资源创建请求"""
    return {
        'type': 'workflow',
        'config': {
            'timeout': 30,
            'retries': 3
        },
        'labels': {
            'env': 'test',
            'team': 'qa'
        }
    }
