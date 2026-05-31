"""
Mock 模块 - 用于单元测试
"""
import sys
from unittest.mock import MagicMock

# 创建mock的Prisma模块
mock_prisma = MagicMock()
sys.modules['@prisma/client'] = mock_prisma

# 创建mock的service模块
class MockChaosInjectionService:
    """Mock故障注入服务"""
    
    @staticmethod
    def createScenario(data):
        return {
            'scenarioId': 'scn-mock-001',
            'name': data['name'],
            'faultType': data['faultType'],
            'targetScope': data['targetScope'],
            'parameters': data['parameters'],
            'autoRollback': data['autoRollback'],
            'rollbackConfig': data.get('rollbackConfig'),
            'status': 'draft',
            'createdBy': data['createdBy'],
            'createdAt': '2024-01-01T00:00:00Z',
            'updatedAt': '2024-01-01T00:00:00Z',
        }
    
    @staticmethod
    def getScenario(scenarioId):
        return {
            'scenarioId': scenarioId,
            'name': 'Mock Scenario',
            'faultType': 'network_delay',
            'targetScope': {'namespace': 'test'},
            'parameters': {},
            'autoRollback': True,
            'status': 'active',
            'createdBy': 'test-user',
            'createdAt': '2024-01-01T00:00:00Z',
            'updatedAt': '2024-01-01T00:00:00Z',
        }
    
    @staticmethod
    def listScenarios(params):
        return {
            'items': [],
            'total': 0,
            'page': params['page'],
            'pageSize': params['pageSize'],
            'totalPages': 0,
        }
    
    @staticmethod
    def updateScenario(scenarioId, data):
        result = MockChaosInjectionService.getScenario(scenarioId)
        result.update(data)
        return result
    
    @staticmethod
    def deleteScenario(scenarioId):
        return {'message': 'Scenario deleted'}
    
    @staticmethod
    def startInjection(data):
        return {
            'injectionId': 'inj-mock-001',
            'scenarioId': data['scenarioId'],
            'targetIds': data.get('targetIds', []),
            'status': 'injecting',
            'startedAt': '2024-01-01T00:00:00Z',
            'createdAt': '2024-01-01T00:00:00Z',
            'updatedAt': '2024-01-01T00:00:00Z',
        }
    
    @staticmethod
    def getInjection(injectionId):
        return {
            'injectionId': injectionId,
            'scenarioId': 'scn-mock-001',
            'targetIds': ['target-1'],
            'status': 'active',
            'startedAt': '2024-01-01T00:00:00Z',
            'createdAt': '2024-01-01T00:00:00Z',
            'updatedAt': '2024-01-01T00:00:00Z',
        }
    
    @staticmethod
    def rollbackInjection(injectionId):
        result = MockChaosInjectionService.getInjection(injectionId)
        result['status'] = 'rolling_back'
        result['rollbackAt'] = '2024-01-01T00:05:00Z'
        return result


class MockCommandAuditService:
    """Mock命令审计服务"""
    
    @staticmethod
    def persistCommand(data):
        return {
            'commandId': 'cmd-mock-001',
            'commandType': data['commandType'],
            'aggregateId': data['aggregateId'],
            'payload': data['payload'],
            'metadata': data.get('metadata'),
            'actorId': data.get('actorId'),
            'timestamp': '2024-01-01T00:00:00Z',
        }
    
    @staticmethod
    def getCommand(commandId):
        return {
            'commandId': commandId,
            'commandType': 'resource.create',
            'aggregateId': 'agg-mock-001',
            'payload': {},
            'actorId': 'test-user',
            'timestamp': '2024-01-01T00:00:00Z',
        }
    
    @staticmethod
    def listCommands(params, aggregateId=None, commandType=None):
        return {
            'items': [],
            'total': 0,
            'page': params['page'],
            'pageSize': params['pageSize'],
            'totalPages': 0,
        }
    
    @staticmethod
    def getCommandsByAggregate(aggregateId):
        return []
    
    @staticmethod
    def createAuditLog(data):
        return {
            'logId': 'log-mock-001',
            'action': data['action'],
            'actorId': data['actorId'],
            'resourceId': data.get('resourceId'),
            'details': data.get('details', {}),
            'commandId': data.get('commandId'),
            'timestamp': '2024-01-01T00:00:00Z',
        }
    
    @staticmethod
    def getAuditLog(logId):
        return {
            'logId': logId,
            'action': 'resource.created',
            'actorId': 'test-user',
            'resourceId': 'res-mock-001',
            'details': {},
            'timestamp': '2024-01-01T00:00:00Z',
        }
    
    @staticmethod
    def listAuditLogs(params, actorId=None, action=None, resourceId=None):
        return {
            'items': [],
            'total': 0,
            'page': params['page'],
            'pageSize': params['pageSize'],
            'totalPages': 0,
        }
    
    @staticmethod
    def getAuditLogsByCommand(commandId):
        return []
    
    @staticmethod
    def generateComplianceReport(data):
        return {
            'reportId': 'rpt-mock-001',
            'startDate': data['startDate'],
            'endDate': data['endDate'],
            'totalCommands': 0,
            'totalAuditLogs': 0,
            'summary': {},
            'entries': [],
            'generatedAt': '2024-01-01T00:00:00Z',
        }


class MockDnsProxyService:
    """Mock DNS代理服务"""
    pass


class MockMtlsCertService:
    """Mock mTLS证书服务"""
    pass


class MockEventStoreService:
    """Mock事件存储服务"""
    pass


class MockImageDistributionService:
    """Mock镜像分发服务"""
    pass


class MockSidecarLifecycleService:
    """Mock Sidecar生命周期服务"""
    pass


# 导出mock服务
chaosInjection = MockChaosInjectionService
commandAudit = MockCommandAuditService
dnsProxy = MockDnsProxyService
mtlsCert = MockMtlsCertService
eventStore = MockEventStoreService
imageDistribution = MockImageDistributionService
sidecarLifecycle = MockSidecarLifecycleService
