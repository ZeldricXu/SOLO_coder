class EdgePlatformException(Exception):
    """基础异常类"""
    pass


class TaskException(EdgePlatformException):
    """任务异常"""
    pass


class TaskNotFoundException(TaskException):
    """任务未找到"""
    pass


class TaskConflictException(TaskException):
    """任务冲突（乐观锁失败）"""
    pass


class TaskTimeoutException(TaskException):
    """任务超时"""
    pass


class OTAException(EdgePlatformException):
    """OTA升级异常"""
    pass


class DeltaGenerationException(OTAException):
    """差分包生成异常"""
    pass


class RollbackException(OTAException):
    """回滚异常"""
    pass


class DeviceShadowException(EdgePlatformException):
    """设备影子异常"""
    pass


class ShadowSyncException(DeviceShadowException):
    """影子同步异常"""
    pass


class RuleEngineException(EdgePlatformException):
    """规则引擎异常"""
    pass


class RuleExecutionException(RuleEngineException):
    """规则执行异常"""
    pass


class StorageException(EdgePlatformException):
    """存储异常"""
    pass


class ObjectNotFoundException(StorageException):
    """对象未找到"""
    pass


class NotificationException(EdgePlatformException):
    """通知异常"""
    pass


class ProtocolException(EdgePlatformException):
    """协议异常"""
    pass


class MonitoringException(EdgePlatformException):
    """监控异常"""
    pass


class InferenceException(EdgePlatformException):
    """推理异常"""
    pass


class ModelNotFoundException(InferenceException):
    """模型未找到"""
    pass
