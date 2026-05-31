"""
测试数据构造器模块
统一管理所有测试用例的数据构造，遵循Builder设计模式
"""

import copy
import random
import string
import time
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional, Union


class BaseBuilder:
    """基础构造器类"""

    def __init__(self):
        self._data: Dict[str, Any] = {}

    def build(self) -> Dict[str, Any]:
        """构建最终数据"""
        return copy.deepcopy(self._data)

    def reset(self):
        """重置构造器状态"""
        self._data = {}
        return self

    def with_field(self, key: str, value: Any):
        """设置指定字段"""
        self._data[key] = value
        return self


class LogLevelConfigBuilder(BaseBuilder):
    """
    日志级别配置构造器
    用于构造日志模块的测试数据
    """

    VALID_LEVELS = ["debug", "info", "warn", "error", "dpanic", "panic", "fatal"]
    INVALID_LEVELS = ["invalid", "DEBUG", "", "none", "123", "trace"]

    def __init__(self):
        super().__init__()
        self._data = {
            "namespace": "default",
            "component": "test.component",
            "level": "info",
            "updated_by": "test_user",
        }

    def with_namespace(self, namespace: str):
        """设置命名空间"""
        return self.with_field("namespace", namespace)

    def with_component(self, component: str):
        """设置组件名"""
        return self.with_field("component", component)

    def with_level(self, level: str):
        """设置日志级别"""
        return self.with_field("level", level)

    def with_updated_by(self, updated_by: str):
        """设置更新者"""
        return self.with_field("updated_by", updated_by)

    def with_valid_level(self, index: int = 0):
        """使用有效日志级别"""
        return self.with_level(self.VALID_LEVELS[index % len(self.VALID_LEVELS)])

    def with_invalid_level(self, index: int = 0):
        """使用无效日志级别（用于边界测试）"""
        return self.with_level(self.INVALID_LEVELS[index % len(self.INVALID_LEVELS)])

    def with_empty_namespace(self):
        """设置空命名空间（边界条件）"""
        return self.with_namespace("")

    def with_empty_component(self):
        """设置空组件名（边界条件）"""
        return self.with_component("")

    def with_special_chars_component(self):
        """使用特殊字符组件名（边界条件）"""
        special_chars = "!@#$%^&*()_+-=[]{}|;:,.<>?/~`"
        return self.with_component(f"component.{special_chars}")

    def with_long_component_name(self, length: int = 256):
        """使用超长组件名（边界条件）"""
        long_name = "".join(random.choices(string.ascii_letters, k=length))
        return self.with_component(long_name)

    def with_unicode_component(self):
        """使用Unicode组件名（边界条件）"""
        return self.with_component("组件.测试.コンポーネント.компонент")

    def as_request(self) -> Dict[str, Any]:
        """作为API请求体"""
        data = self.build()
        return {k: v for k, v in data.items() if k in ["namespace", "component", "level", "updated_by"]}

    def as_response(self, config_id: Optional[str] = None) -> Dict[str, Any]:
        """作为API响应体"""
        data = self.build()
        now = datetime.utcnow().isoformat() + "Z"
        return {
            "id": config_id or f"log_{self._random_id()}",
            "namespace": data["namespace"],
            "component": data["component"],
            "level": data["level"],
            "updated_at": now,
            "updated_by": data["updated_by"],
        }

    @staticmethod
    def _random_id() -> str:
        """生成随机ID"""
        return "".join(random.choices(string.hexdigits.lower(), k=13))


class ScheduledTaskBuilder(BaseBuilder):
    """
    调度任务构造器
    用于构造调度模块的测试数据
    """

    VALID_STATUSES = ["pending", "running", "completed", "failed", "disabled"]
    VALID_TASK_TYPES = ["data_processing", "report_generation", "backup", "cleanup", "notification"]

    def __init__(self):
        super().__init__()
        self._data = {
            "name": "Test Task",
            "description": "This is a test task",
            "type": "data_processing",
            "cron_expr": "0 * * * *",
            "payload": {"key": "value"},
            "depends_on": [],
            "timeout_seconds": 30,
            "retries": 3,
            "enabled": True,
        }

    def with_name(self, name: str):
        return self.with_field("name", name)

    def with_description(self, description: str):
        return self.with_field("description", description)

    def with_type(self, task_type: str):
        return self.with_field("type", task_type)

    def with_cron_expr(self, cron_expr: str):
        return self.with_field("cron_expr", cron_expr)

    def with_payload(self, payload: Dict[str, Any]):
        return self.with_field("payload", payload)

    def with_depends_on(self, depends_on: List[str]):
        return self.with_field("depends_on", depends_on)

    def with_timeout_seconds(self, timeout: int):
        return self.with_field("timeout_seconds", timeout)

    def with_retries(self, retries: int):
        return self.with_field("retries", retries)

    def with_enabled(self, enabled: bool):
        return self.with_field("enabled", enabled)

    def with_status(self, status: str):
        return self.with_field("status", status)

    def with_empty_name(self):
        """空任务名（边界条件）"""
        return self.with_name("")

    def with_very_long_name(self, length: int = 512):
        """超长任务名（边界条件）"""
        return self.with_name("".join(random.choices(string.ascii_letters, k=length)))

    def with_negative_timeout(self):
        """负超时时间（边界条件）"""
        return self.with_timeout_seconds(-1)

    def with_zero_timeout(self):
        """零超时时间（边界条件）"""
        return self.with_timeout_seconds(0)

    def with_invalid_cron(self):
        """无效Cron表达式（边界条件）"""
        return self.with_cron_expr("invalid cron")

    def with_empty_cron(self):
        """空Cron表达式（边界条件）"""
        return self.with_cron_expr("")

    def with_circular_dependency(self, task_id: str):
        """循环依赖（边界条件）"""
        return self.with_depends_on([task_id])

    def with_complex_dependencies(self, count: int = 5):
        """复杂依赖链（边界条件）"""
        deps = [f"dep_{i}_{self._random_id()}" for i in range(count)]
        return self.with_depends_on(deps)

    def with_large_payload(self, size_kb: int = 100):
        """大数据量Payload（边界条件）"""
        large_data = {
            "data": "".join(random.choices(string.ascii_letters + string.digits, k=size_kb * 1024))
        }
        return self.with_payload(large_data)

    def with_disabled(self):
        """禁用状态"""
        return self.with_enabled(False)

    def with_invalid_type(self):
        """无效任务类型"""
        return self.with_type("invalid_type")

    def as_request(self) -> Dict[str, Any]:
        """作为创建任务的请求体"""
        return self.build()

    def as_response(self, task_id: Optional[str] = None) -> Dict[str, Any]:
        """作为API响应体"""
        data = self.build()
        now = datetime.utcnow().isoformat() + "Z"
        return {
            "id": task_id or f"task_{self._random_id()}",
            "name": data["name"],
            "description": data["description"],
            "type": data["type"],
            "cron_expr": data["cron_expr"],
            "payload": data["payload"],
            "depends_on": data["depends_on"],
            "timeout_seconds": data["timeout_seconds"],
            "retries": data["retries"],
            "enabled": data["enabled"],
            "status": data.get("status", "pending"),
            "created_at": now,
            "updated_at": now,
        }

    @staticmethod
    def _random_id() -> str:
        return "".join(random.choices(string.hexdigits.lower(), k=13))


class TaskExecutionBuilder(BaseBuilder):
    """
    任务执行记录构造器
    """

    def __init__(self):
        super().__init__()
        self._data = {
            "task_id": "task_test123",
            "status": "completed",
            "result": "Task executed successfully",
        }

    def with_task_id(self, task_id: str):
        return self.with_field("task_id", task_id)

    def with_status(self, status: str):
        return self.with_field("status", status)

    def with_result(self, result: str):
        return self.with_field("result", result)

    def with_error(self, error_msg: str):
        return self.with_field("error_msg", error_msg)

    def as_running(self):
        return self.with_status("running")

    def as_completed(self, result: Optional[str] = None):
        if result:
            self.with_result(result)
        return self.with_status("completed")

    def as_failed(self, error_msg: str = "Task failed"):
        return self.with_status("failed").with_error(error_msg)

    def as_response(self, execution_id: Optional[str] = None) -> Dict[str, Any]:
        data = self.build()
        now = datetime.utcnow()
        response = {
            "id": execution_id or f"exec_{self._random_id()}",
            "task_id": data["task_id"],
            "status": data["status"],
            "start_time": now.isoformat() + "Z",
        }
        if data["status"] != "running":
            response["end_time"] = (now + timedelta(seconds=5)).isoformat() + "Z"
        if "result" in data:
            response["result"] = data["result"]
        if "error_msg" in data:
            response["error_msg"] = data["error_msg"]
        return response

    @staticmethod
    def _random_id() -> str:
        return "".join(random.choices(string.hexdigits.lower(), k=13))


class MetricRecordBuilder(BaseBuilder):
    """
    监控指标记录构造器
    """

    VALID_METRIC_TYPES = ["counter", "gauge", "histogram", "summary"]
    INVALID_METRIC_TYPES = ["invalid", "", "COUNTER", "metric", "123"]

    def __init__(self):
        super().__init__()
        self._data = {
            "type": "counter",
            "name": "test_metric_total",
            "value": 1.0,
            "labels": {"host": "node-1", "region": "cn-east"},
        }

    def with_type(self, metric_type: str):
        return self.with_field("type", metric_type)

    def with_name(self, name: str):
        return self.with_field("name", name)

    def with_value(self, value: float):
        return self.with_field("value", value)

    def with_labels(self, labels: Dict[str, str]):
        return self.with_field("labels", labels)

    def with_valid_type(self, index: int = 0):
        return self.with_type(self.VALID_METRIC_TYPES[index % len(self.VALID_METRIC_TYPES)])

    def with_invalid_type(self, index: int = 0):
        return self.with_type(self.INVALID_METRIC_TYPES[index % len(self.INVALID_METRIC_TYPES)])

    def with_empty_name(self):
        return self.with_name("")

    def with_negative_value(self):
        return self.with_value(-1.0)

    def with_zero_value(self):
        return self.with_value(0.0)

    def with_large_value(self):
        return self.with_value(1e18)

    def with_empty_labels(self):
        return self.with_labels({})

    def with_many_labels(self, count: int = 50):
        labels = {f"label_{i}": f"value_{i}" for i in range(count)}
        return self.with_labels(labels)

    def as_request(self) -> Dict[str, Any]:
        return self.build()


class StatsSnapshotBuilder(BaseBuilder):
    """
    统计快照构造器
    """

    def __init__(self):
        super().__init__()
        self._data = {
            "dimensions": {"host": "node-1", "region": "cn-east"},
        }

    def with_dimensions(self, dimensions: Dict[str, str]):
        return self.with_field("dimensions", dimensions)

    def with_empty_dimensions(self):
        return self.with_dimensions({})

    def as_request(self) -> Dict[str, Any]:
        return self.build()

    def as_response(self, snapshot_id: Optional[str] = None) -> Dict[str, Any]:
        data = self.build()
        return {
            "snapshot_id": snapshot_id or f"snap_{self._random_id()}",
            "timestamp": datetime.utcnow().isoformat() + "Z",
            "metrics": {
                "timestamp": time.time(),
                "active_goroutines": random.randint(1, 100),
                "throughput": random.uniform(100, 2000),
                "latency_p99": random.uniform(50, 500),
                "error_rate": random.uniform(0, 0.1),
            },
            "dimensions": data["dimensions"],
        }

    @staticmethod
    def _random_id() -> str:
        return "".join(random.choices(string.hexdigits.lower(), k=13))


class MockResponseBuilder:
    """
    Mock响应构造器
    用于构造unittest.mock的返回值
    """

    @staticmethod
    def success(data: Any = None, code: int = 200) -> Dict[str, Any]:
        """构造成功响应"""
        response = {"code": code}
        if data is not None:
            response["data"] = data
        return response

    @staticmethod
    def error(code: int, message: str) -> Dict[str, Any]:
        """构造错误响应"""
        return {"code": code, "error": message}

    @staticmethod
    def validation_error(message: str = "Validation failed") -> Dict[str, Any]:
        """构造参数校验错误响应"""
        return MockResponseBuilder.error(400, message)

    @staticmethod
    def not_found(message: str = "Resource not found") -> Dict[str, Any]:
        """构造资源不存在响应"""
        return MockResponseBuilder.error(404, message)

    @staticmethod
    def internal_error(message: str = "Internal server error") -> Dict[str, Any]:
        """构造内部错误响应"""
        return MockResponseBuilder.error(500, message)

    @staticmethod
    def conflict(message: str = "Resource conflict") -> Dict[str, Any]:
        """构造冲突响应"""
        return MockResponseBuilder.error(409, message)

    @staticmethod
    def timeout(message: str = "Request timeout") -> Dict[str, Any]:
        """构造超时响应"""
        return MockResponseBuilder.error(504, message)

    @staticmethod
    def async_response(batch_id: str, results: Optional[List] = None) -> Dict[str, Any]:
        """构造批量操作响应"""
        return {
            "code": 200,
            "data": {
                "batch_id": batch_id,
                "results": results or [],
            },
        }


class TestDataGenerator:
    """
    测试数据生成器
    用于生成批量测试数据和边界条件数据
    """

    @staticmethod
    def generate_log_level_configs(count: int = 10) -> List[Dict[str, Any]]:
        """生成多个日志级别配置"""
        configs = []
        components = ["api", "worker", "scheduler", "monitoring", "notification"]
        namespaces = ["production", "staging", "development", "testing"]

        for i in range(count):
            builder = LogLevelConfigBuilder()
            builder.with_namespace(random.choice(namespaces))
            builder.with_component(f"{random.choice(components)}.instance_{i}")
            builder.with_valid_level(i % len(LogLevelConfigBuilder.VALID_LEVELS))
            configs.append(builder.as_request())
        return configs

    @staticmethod
    def generate_scheduled_tasks(count: int = 10) -> List[Dict[str, Any]]:
        """生成多个调度任务"""
        tasks = []
        cron_expressions = [
            "0 * * * *", "*/5 * * * *", "0 0 * * *",
            "30 2 * * *", "0 9-17 * * 1-5",
        ]

        for i in range(count):
            builder = ScheduledTaskBuilder()
            builder.with_name(f"Task {i + 1}")
            builder.with_description(f"Description for task {i + 1}")
            builder.with_cron_expr(random.choice(cron_expressions))
            builder.with_timeout_seconds(random.randint(10, 300))
            builder.with_retries(random.randint(0, 5))
            tasks.append(builder.as_request())
        return tasks

    @staticmethod
    def generate_metric_records(count: int = 100) -> List[Dict[str, Any]]:
        """生成多个监控指标记录"""
        records = []
        metric_names = [
            "http_requests_total", "http_request_duration_seconds",
            "task_executions_total", "active_goroutines",
            "queue_size", "memory_usage_bytes", "cpu_usage_percent",
        ]

        for i in range(count):
            builder = MetricRecordBuilder()
            builder.with_valid_type(i % 4)
            builder.with_name(random.choice(metric_names))
            builder.with_value(random.uniform(0, 1000))
            builder.with_labels({
                "host": f"node-{random.randint(1, 10)}",
                "region": random.choice(["cn-east", "cn-west", "cn-south", "cn-north"]),
                "env": random.choice(["prod", "staging", "dev"]),
            })
            records.append(builder.as_request())
        return records

    @staticmethod
    def generate_boundary_test_cases() -> Dict[str, List[Dict[str, Any]]]:
        """
        生成边界条件测试用例
        返回按模块分类的边界测试数据
        """
        return {
            "log_level": [
                LogLevelConfigBuilder().with_empty_namespace().as_request(),
                LogLevelConfigBuilder().with_empty_component().as_request(),
                LogLevelConfigBuilder().with_invalid_level(0).as_request(),
                LogLevelConfigBuilder().with_invalid_level(1).as_request(),
                LogLevelConfigBuilder().with_special_chars_component().as_request(),
                LogLevelConfigBuilder().with_long_component_name(128).as_request(),
                LogLevelConfigBuilder().with_long_component_name(256).as_request(),
                LogLevelConfigBuilder().with_unicode_component().as_request(),
            ],
            "scheduler": [
                ScheduledTaskBuilder().with_empty_name().as_request(),
                ScheduledTaskBuilder().with_very_long_name(512).as_request(),
                ScheduledTaskBuilder().with_negative_timeout().as_request(),
                ScheduledTaskBuilder().with_zero_timeout().as_request(),
                ScheduledTaskBuilder().with_invalid_cron().as_request(),
                ScheduledTaskBuilder().with_empty_cron().as_request(),
                ScheduledTaskBuilder().with_large_payload(100).as_request(),
                ScheduledTaskBuilder().with_complex_dependencies(10).as_request(),
                ScheduledTaskBuilder().with_invalid_type().as_request(),
            ],
            "monitoring": [
                MetricRecordBuilder().with_invalid_type(0).as_request(),
                MetricRecordBuilder().with_invalid_type(1).as_request(),
                MetricRecordBuilder().with_empty_name().as_request(),
                MetricRecordBuilder().with_negative_value().as_request(),
                MetricRecordBuilder().with_large_value().as_request(),
                MetricRecordBuilder().with_many_labels(30).as_request(),
            ],
        }

    @staticmethod
    def generate_error_scenarios() -> List[Dict[str, Any]]:
        """
        生成错误场景测试用例
        用于测试事务回滚等异常处理逻辑
        """
        return [
            {
                "scenario": "database_connection_error",
                "error_type": "connection",
                "expected_rollback": True,
                "description": "数据库连接断开时应正确回滚",
            },
            {
                "scenario": "unique_key_violation",
                "error_type": "constraint",
                "expected_rollback": True,
                "description": "唯一键冲突时应正确回滚",
            },
            {
                "scenario": "deadlock_detected",
                "error_type": "deadlock",
                "expected_rollback": True,
                "description": "死锁检测时应正确回滚",
            },
            {
                "scenario": "network_timeout",
                "error_type": "timeout",
                "expected_rollback": True,
                "description": "网络超时时应正确回滚",
            },
            {
                "scenario": "insufficient_permissions",
                "error_type": "permission",
                "expected_rollback": True,
                "description": "权限不足时应正确回滚",
            },
            {
                "scenario": "data_validation_failed",
                "error_type": "validation",
                "expected_rollback": True,
                "description": "数据校验失败时应正确回滚",
            },
            {
                "scenario": "partial_write_failure",
                "error_type": "partial",
                "expected_rollback": True,
                "description": "部分写入失败时应完全回滚",
            },
            {
                "scenario": "concurrent_modification",
                "error_type": "concurrency",
                "expected_rollback": True,
                "description": "并发修改冲突时应正确回滚",
            },
        ]

    @staticmethod
    def generate_id_list(prefix: str, count: int = 5) -> List[str]:
        """生成ID列表"""
        return [f"{prefix}_{''.join(random.choices(string.hexdigits.lower(), k=13))}" for _ in range(count)]
