"""
命令溯源与审计模块 - 边界值测试
"""
import pytest
import requests
from typing import Dict, Any
from datetime import datetime, timedelta
import uuid

from tests.client import ChaosLabClient
from tests.conftest import TestConfig

pytestmark = [pytest.mark.audit, pytest.mark.boundary]


class TestCommandBoundaries:
    """命令边界值测试"""
    
    def test_command_empty_type(self, api_client: requests.Session,
                                 config: TestConfig,
                                 command_data: Dict[str, Any]):
        """测试命令类型为空 - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        command_data['commandType'] = ''
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.create_command(command_data)
        
        assert exc_info.value.response.status_code in [400, 422]
    
    def test_command_type_too_long(self, api_client: requests.Session,
                                    config: TestConfig,
                                    command_data: Dict[str, Any]):
        """测试命令类型过长 - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        command_data['commandType'] = 'a' * 1000
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.create_command(command_data)
        
        assert exc_info.value.response.status_code in [400, 422]
    
    def test_command_empty_aggregate_id(self, api_client: requests.Session,
                                         config: TestConfig,
                                         command_data: Dict[str, Any]):
        """测试聚合ID为空 - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        command_data['aggregateId'] = ''
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.create_command(command_data)
        
        assert exc_info.value.response.status_code in [400, 422]
    
    def test_command_empty_payload(self, api_client: requests.Session,
                                    config: TestConfig,
                                    command_data: Dict[str, Any]):
        """测试空payload - 应该成功（允许空payload）"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        command_data['payload'] = {}
        
        result = client.create_command(command_data)
        assert result['payload'] == {}
    
    def test_command_large_payload(self, api_client: requests.Session,
                                    config: TestConfig,
                                    command_data: Dict[str, Any]):
        """测试大型payload"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        # 创建一个大型payload
        large_payload = {
            f'key_{i}': f'value_{i}' * 100
            for i in range(1000)
        }
        command_data['payload'] = large_payload
        
        try:
            result = client.create_command(command_data)
            assert result['payload'] == large_payload
        except requests.HTTPError as e:
            # 如果失败，应该是400/413，而不是500
            assert e.response.status_code in [400, 413, 422]
    
    def test_command_special_characters(self, api_client: requests.Session,
                                          config: TestConfig,
                                          command_data: Dict[str, Any]):
        """测试特殊字符"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        special_types = [
            'command-with-dashes',
            'command_with_underscores',
            'command.with.dots',
            'command/with/slashes',
            'command:with:colons',
            '命令-中文类型',
            'command-🔥-emoji',
            '<script>alert(1)</script>',
            "' OR 1=1--",
        ]
        
        for cmd_type in special_types:
            command_data['commandType'] = cmd_type
            try:
                result = client.create_command(command_data)
                assert result['commandType'] == cmd_type
            except requests.HTTPError as e:
                assert e.response.status_code in [400, 422]
    
    def test_command_empty_metadata(self, api_client: requests.Session,
                                     config: TestConfig,
                                     command_data: Dict[str, Any]):
        """测试空metadata - 应该成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        if 'metadata' in command_data:
            del command_data['metadata']
        
        result = client.create_command(command_data)
        assert result.get('metadata') is None or result.get('metadata') == {}
    
    def test_command_no_actor(self, api_client: requests.Session,
                               config: TestConfig,
                               command_data: Dict[str, Any]):
        """测试没有actorId - 应该成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        if 'actorId' in command_data:
            del command_data['actorId']
        
        result = client.create_command(command_data)
        assert result.get('actorId') is None
    
    def test_command_pagination_boundaries(self, api_client: requests.Session,
                                            config: TestConfig,
                                            command_data: Dict[str, Any]):
        """测试命令分页边界"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        # 创建一些命令
        for i in range(5):
            data = command_data.copy()
            data['commandType'] = f'boundary.test.{i}'
            data['aggregateId'] = f'boundary-aggregate-{i}'
            client.create_command(data)
        
        # 测试边界值
        test_cases = [
            (0, 20),   # page=0
            (1, 0),    # page_size=0
            (1, 1),    # page_size=1
            (1, 1000), # 超大page_size
            (999, 20), # 超大page
            (-1, 20),  # 负数page
            (1, -1),   # 负数page_size
        ]
        
        for page, page_size in test_cases:
            try:
                result = client.list_commands(page=page, page_size=page_size)
                # 如果成功，验证基本结构
                assert 'items' in result
                assert 'total' in result
            except requests.HTTPError as e:
                # 如果失败，应该是400/422
                assert e.response.status_code in [400, 422]
    
    def test_command_nonexistent_get(self, api_client: requests.Session,
                                       config: TestConfig):
        """测试获取不存在的命令"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.get_command('nonexistent-command-id')
        
        assert exc_info.value.response.status_code in [400, 404]
    
    def test_command_aggregate_with_no_commands(self, api_client: requests.Session,
                                                 config: TestConfig):
        """测试获取没有命令的聚合"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        result = client.get_commands_by_aggregate(f'nonexistent-aggregate-{uuid.uuid4()}')
        assert isinstance(result, list)
        assert len(result) == 0


class TestAuditLogBoundaries:
    """审计日志边界值测试"""
    
    def test_audit_empty_action(self, api_client: requests.Session,
                                 config: TestConfig,
                                 audit_log_data: Dict[str, Any]):
        """测试空action - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        audit_log_data['action'] = ''
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.create_audit_log(audit_log_data)
        
        assert exc_info.value.response.status_code in [400, 422]
    
    def test_audit_action_too_long(self, api_client: requests.Session,
                                    config: TestConfig,
                                    audit_log_data: Dict[str, Any]):
        """测试过长的action"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        audit_log_data['action'] = 'a' * 1000
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.create_audit_log(audit_log_data)
        
        assert exc_info.value.response.status_code in [400, 422]
    
    def test_audit_empty_actor(self, api_client: requests.Session,
                                config: TestConfig,
                                audit_log_data: Dict[str, Any]):
        """测试空actorId - 应该失败"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        audit_log_data['actorId'] = ''
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.create_audit_log(audit_log_data)
        
        assert exc_info.value.response.status_code in [400, 422]
    
    def test_audit_empty_details(self, api_client: requests.Session,
                                  config: TestConfig,
                                  audit_log_data: Dict[str, Any]):
        """测试空details - 应该成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        audit_log_data['details'] = {}
        
        result = client.create_audit_log(audit_log_data)
        assert result['details'] == {}
    
    def test_audit_large_details(self, api_client: requests.Session,
                                   config: TestConfig,
                                   audit_log_data: Dict[str, Any]):
        """测试大型details"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        large_details = {
            'changes': [f'change_{i}' for i in range(1000)],
            'old_value': {'data': 'x' * 10000},
            'new_value': {'data': 'y' * 10000},
        }
        audit_log_data['details'] = large_details
        
        try:
            result = client.create_audit_log(audit_log_data)
            assert result['details'] == large_details
        except requests.HTTPError as e:
            assert e.response.status_code in [400, 413, 422]
    
    def test_audit_no_resource_id(self, api_client: requests.Session,
                                    config: TestConfig,
                                    audit_log_data: Dict[str, Any]):
        """测试没有resourceId - 应该成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        if 'resourceId' in audit_log_data:
            del audit_log_data['resourceId']
        
        result = client.create_audit_log(audit_log_data)
        assert result.get('resourceId') is None
    
    def test_audit_pagination(self, api_client: requests.Session,
                               config: TestConfig,
                               audit_log_data: Dict[str, Any]):
        """测试审计日志分页"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        for i in range(5):
            data = audit_log_data.copy()
            data['action'] = f'audit.boundary.{i}'
            client.create_audit_log(data)
        
        # 测试各种分页参数
        result = client.list_audit_logs(page=1, page_size=2)
        assert len(result['items']) <= 2
        
        result = client.list_audit_logs(page=999, page_size=10)
        assert isinstance(result['items'], list)
    
    def test_audit_nonexistent_log(self, api_client: requests.Session,
                                     config: TestConfig):
        """测试获取不存在的审计日志"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.get_audit_log('nonexistent-log-id')
        
        assert exc_info.value.response.status_code in [400, 404]
    
    def test_audit_command_logs_nonexistent(self, api_client: requests.Session,
                                             config: TestConfig):
        """测试获取不存在命令的审计日志"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        logs = client._request('GET', f'audit/commands/nonexistent-command/audit-logs').json()
        assert isinstance(logs.get('data'), list)


class TestComplianceReportBoundaries:
    """合规报告边界值测试"""
    
    def test_report_empty_date_range(self, api_client: requests.Session,
                                      config: TestConfig):
        """测试空的日期范围"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.generate_compliance_report(
                start_date='',
                end_date=''
            )
        
        assert exc_info.value.response.status_code in [400, 422]
    
    def test_report_invalid_date_format(self, api_client: requests.Session,
                                         config: TestConfig):
        """测试无效的日期格式"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        invalid_dates = [
            ('not-a-date', '2024-01-01T00:00:00Z'),
            ('2024-01-01T00:00:00Z', 'not-a-date'),
            ('2024/01/01', '2024/01/02'),
            ('01-01-2024', '02-01-2024'),
        ]
        
        for start, end in invalid_dates:
            try:
                client.generate_compliance_report(start_date=start, end_date=end)
            except requests.HTTPError as e:
                assert e.response.status_code in [400, 422]
    
    def test_report_future_dates(self, api_client: requests.Session,
                                  config: TestConfig):
        """测试未来日期"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        future = datetime.utcnow() + timedelta(days=365)
        far_future = datetime.utcnow() + timedelta(days=730)
        
        result = client.generate_compliance_report(
            start_date=future.isoformat() + 'Z',
            end_date=far_future.isoformat() + 'Z'
        )
        
        assert result['totalCommands'] == 0
        assert result['totalAuditLogs'] == 0
    
    def test_report_start_after_end(self, api_client: requests.Session,
                                      config: TestConfig):
        """测试开始日期晚于结束日期"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        end_date = datetime.utcnow()
        start_date = end_date + timedelta(days=1)
        
        try:
            result = client.generate_compliance_report(
                start_date=start_date.isoformat() + 'Z',
                end_date=end_date.isoformat() + 'Z'
            )
            # 如果允许，应该返回空结果
            assert result['totalCommands'] == 0
        except requests.HTTPError as e:
            # 或者报错
            assert e.response.status_code in [400, 422]
    
    def test_report_invalid_format(self, api_client: requests.Session,
                                    config: TestConfig):
        """测试无效的报告格式"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        end_date = datetime.utcnow()
        start_date = end_date - timedelta(days=1)
        
        with pytest.raises(requests.HTTPError) as exc_info:
            client.generate_compliance_report(
                start_date=start_date.isoformat() + 'Z',
                end_date=end_date.isoformat() + 'Z',
                format='invalid_format'
            )
        
        assert exc_info.value.response.status_code in [400, 422]
    
    def test_report_large_date_range(self, api_client: requests.Session,
                                       config: TestConfig):
        """测试很大的日期范围"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        end_date = datetime.utcnow()
        start_date = end_date - timedelta(days=365 * 10)  # 10年
        
        result = client.generate_compliance_report(
            start_date=start_date.isoformat() + 'Z',
            end_date=end_date.isoformat() + 'Z'
        )
        
        assert 'reportId' in result
        assert 'entries' in result
        # 验证条目数量被限制
        assert len(result['entries']) <= 10000  # 服务端应该有最大限制
