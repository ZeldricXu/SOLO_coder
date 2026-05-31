"""
命令溯源与审计模块 - 正常业务流程测试
"""
import pytest
import requests
from typing import Dict, Any
from datetime import datetime, timedelta

from tests.client import ChaosLabClient
from tests.conftest import TestConfig

pytestmark = [pytest.mark.audit, pytest.mark.integration]


class TestCommandFlow:
    """命令正常流程测试"""
    
    def test_create_command_success(self, api_client: requests.Session,
                                    config: TestConfig,
                                    command_data: Dict[str, Any]):
        """测试创建命令成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        result = client.create_command(command_data)
        
        assert result['commandId'] is not None
        assert result['commandType'] == command_data['commandType']
        assert result['aggregateId'] == command_data['aggregateId']
        assert result['payload'] == command_data['payload']
        assert result['actorId'] == command_data['actorId']
        assert result['timestamp'] is not None
    
    def test_get_command_success(self, api_client: requests.Session,
                                 config: TestConfig,
                                 command_data: Dict[str, Any]):
        """测试获取命令成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        created = client.create_command(command_data)
        retrieved = client.get_command(created['commandId'])
        
        assert retrieved['commandId'] == created['commandId']
        assert retrieved['commandType'] == created['commandType']
        assert retrieved['payload'] == created['payload']
    
    def test_list_commands_success(self, api_client: requests.Session,
                                   config: TestConfig,
                                   command_data: Dict[str, Any]):
        """测试列出命令成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        # 创建多个命令
        for i in range(5):
            data = command_data.copy()
            data['commandType'] = f'command.type.{i}'
            client.create_command(data)
        
        result = client.list_commands(page=1, page_size=10)
        
        assert result['total'] >= 5
        assert len(result['items']) <= 10
        assert result['page'] == 1
    
    def test_list_commands_by_aggregate(self, api_client: requests.Session,
                                        config: TestConfig,
                                        command_data: Dict[str, Any]):
        """测试按聚合ID列出命令"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        aggregate_id = f'aggregate-test-{datetime.now().timestamp()}'
        
        # 为同一聚合创建多个命令
        for i in range(3):
            data = command_data.copy()
            data['aggregateId'] = aggregate_id
            data['commandType'] = f'type.{i}'
            client.create_command(data)
        
        result = client.list_commands(page=1, page_size=10, aggregate_id=aggregate_id)
        
        assert result['total'] >= 3
        for item in result['items']:
            assert item['aggregateId'] == aggregate_id
    
    def test_get_commands_by_aggregate(self, api_client: requests.Session,
                                        config: TestConfig,
                                        command_data: Dict[str, Any]):
        """测试获取聚合的所有命令"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        aggregate_id = f'aggregate-full-{datetime.now().timestamp()}'
        
        # 创建命令
        created_commands = []
        for i in range(5):
            data = command_data.copy()
            data['aggregateId'] = aggregate_id
            data['commandType'] = f'action.{i}'
            created = client.create_command(data)
            created_commands.append(created)
        
        # 获取所有命令
        commands = client.get_commands_by_aggregate(aggregate_id)
        
        assert len(commands) == 5
        # 验证按时间排序
        for i in range(1, len(commands)):
            assert commands[i]['timestamp'] >= commands[i-1]['timestamp']


class TestAuditLogFlow:
    """审计日志正常流程测试"""
    
    def test_create_audit_log_success(self, api_client: requests.Session,
                                      config: TestConfig,
                                      audit_log_data: Dict[str, Any]):
        """测试创建审计日志成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        result = client.create_audit_log(audit_log_data)
        
        assert result['logId'] is not None
        assert result['action'] == audit_log_data['action']
        assert result['actorId'] == audit_log_data['actorId']
        assert result['resourceId'] == audit_log_data['resourceId']
        assert result['details'] == audit_log_data['details']
        assert result['timestamp'] is not None
    
    def test_get_audit_log_success(self, api_client: requests.Session,
                                    config: TestConfig,
                                    audit_log_data: Dict[str, Any]):
        """测试获取审计日志成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        created = client.create_audit_log(audit_log_data)
        retrieved = client.get_audit_log(created['logId'])
        
        assert retrieved['logId'] == created['logId']
        assert retrieved['action'] == created['action']
    
    def test_list_audit_logs_success(self, api_client: requests.Session,
                                      config: TestConfig,
                                      audit_log_data: Dict[str, Any]):
        """测试列出审计日志成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        # 创建多个审计日志
        for i in range(5):
            data = audit_log_data.copy()
            data['action'] = f'action.{i}'
            client.create_audit_log(data)
        
        result = client.list_audit_logs(page=1, page_size=10)
        
        assert result['total'] >= 5
        assert len(result['items']) <= 10
    
    def test_list_audit_logs_by_actor(self, api_client: requests.Session,
                                        config: TestConfig,
                                        audit_log_data: Dict[str, Any]):
        """测试按操作者筛选审计日志"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        actor_id = f'actor-{datetime.now().timestamp()}'
        
        # 为同一操作者创建日志
        for i in range(3):
            data = audit_log_data.copy()
            data['actorId'] = actor_id
            data['action'] = f'action.{i}'
            client.create_audit_log(data)
        
        result = client.list_audit_logs(page=1, page_size=10, actor_id=actor_id)
        
        assert result['total'] >= 3
        for item in result['items']:
            assert item['actorId'] == actor_id
    
    def test_list_audit_logs_by_action(self, api_client: requests.Session,
                                        config: TestConfig,
                                        audit_log_data: Dict[str, Any]):
        """测试按操作类型筛选审计日志"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        action_type = f'action.unique.{datetime.now().timestamp()}'
        
        # 创建不同类型的日志
        for i in range(5):
            data = audit_log_data.copy()
            if i < 3:
                data['action'] = action_type
            else:
                data['action'] = f'other.action.{i}'
            client.create_audit_log(data)
        
        result = client.list_audit_logs(page=1, page_size=10, action=action_type)
        
        assert result['total'] >= 3


class TestComplianceReportFlow:
    """合规报告正常流程测试"""
    
    def test_generate_compliance_report_success(self, api_client: requests.Session,
                                                config: TestConfig,
                                                command_data: Dict[str, Any],
                                                audit_log_data: Dict[str, Any]):
        """测试生成合规报告成功"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        # 创建一些命令和审计日志
        for i in range(3):
            cmd_data = command_data.copy()
            cmd_data['commandType'] = f'report.test.{i}'
            cmd = client.create_command(cmd_data)
            
            log_data = audit_log_data.copy()
            log_data['action'] = f'report.action.{i}'
            log_data['commandId'] = cmd['commandId']
            client.create_audit_log(log_data)
        
        # 生成报告
        end_date = datetime.utcnow()
        start_date = end_date - timedelta(hours=1)
        
        report = client.generate_compliance_report(
            start_date=start_date.isoformat() + 'Z',
            end_date=end_date.isoformat() + 'Z',
            format='json'
        )
        
        assert report['reportId'] is not None
        assert report['startDate'] is not None
        assert report['endDate'] is not None
        assert 'totalCommands' in report
        assert 'totalAuditLogs' in report
        assert 'entries' in report
        assert report['generatedAt'] is not None


class TestCommandAuditIntegration:
    """命令与审计集成流程测试"""
    
    def test_command_with_audit_log(self, api_client: requests.Session,
                                     config: TestConfig,
                                     command_data: Dict[str, Any],
                                     audit_log_data: Dict[str, Any]):
        """测试命令创建后关联审计日志"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        
        # 1. 创建命令
        command = client.create_command(command_data)
        
        # 2. 创建关联的审计日志
        audit_log_data['commandId'] = command['commandId']
        audit_log = client.create_audit_log(audit_log_data)
        
        assert audit_log['commandId'] == command['commandId']
        
        # 3. 通过命令ID查询审计日志
        logs = client._request('GET', f'audit/commands/{command["commandId"]}/audit-logs').json()
        audit_logs = logs['data']
        
        assert len(audit_logs) >= 1
        assert any(log['logId'] == audit_log['logId'] for log in audit_logs)
    
    def test_full_audit_trail(self, api_client: requests.Session,
                               config: TestConfig,
                               command_data: Dict[str, Any],
                               audit_log_data: Dict[str, Any]):
        """测试完整审计追踪"""
        client = ChaosLabClient(config.BASE_URL, api_client)
        aggregate_id = f'audit-trail-{datetime.now().timestamp()}'
        
        # 模拟一个完整的操作流程
        commands = []
        
        # 1. 创建资源
        command_data['aggregateId'] = aggregate_id
        command_data['commandType'] = 'resource.create'
        command_data['payload'] = {'action': 'create', 'resource': 'test'}
        cmd1 = client.create_command(command_data)
        commands.append(cmd1)
        
        audit_log_data['action'] = 'resource.created'
        audit_log_data['commandId'] = cmd1['commandId']
        client.create_audit_log(audit_log_data)
        
        # 2. 更新资源
        command_data['commandType'] = 'resource.update'
        command_data['payload'] = {'action': 'update', 'status': 'active'}
        cmd2 = client.create_command(command_data)
        commands.append(cmd2)
        
        audit_log_data['action'] = 'resource.updated'
        audit_log_data['commandId'] = cmd2['commandId']
        client.create_audit_log(audit_log_data)
        
        # 3. 删除资源
        command_data['commandType'] = 'resource.delete'
        command_data['payload'] = {'action': 'delete'}
        cmd3 = client.create_command(command_data)
        commands.append(cmd3)
        
        audit_log_data['action'] = 'resource.deleted'
        audit_log_data['commandId'] = cmd3['commandId']
        client.create_audit_log(audit_log_data)
        
        # 验证：获取所有命令
        all_commands = client.get_commands_by_aggregate(aggregate_id)
        assert len(all_commands) == 3
        
        # 验证命令顺序
        command_types = [cmd['commandType'] for cmd in all_commands]
        assert command_types == ['resource.create', 'resource.update', 'resource.delete']
