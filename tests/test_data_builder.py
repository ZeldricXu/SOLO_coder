import random
import string
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional
from dataclasses import dataclass, field


@dataclass
class AuditLogTestData:
    action: str
    actor: str
    resource: str
    details: Dict[str, Any]
    expected_sequence: int


@dataclass
class MaskingTestData:
    original_data: Dict[str, Any]
    role: str
    expected_masked_fields: List[str]
    expected_visible_fields: List[str]


@dataclass
class ShamirTestData:
    secret: bytes
    threshold: int
    total_shares: int
    holders: List[str]


class DataBuilder:
    """测试数据构建器 - 用于生成各种测试场景的数据"""

    def __init__(self, seed: int = 42):
        random.seed(seed)
        self._counter = 0

    def _unique_id(self, prefix: str = "") -> str:
        self._counter += 1
        return f"{prefix}{self._counter:04d}"

    def _random_string(self, length: int = 10) -> str:
        return ''.join(random.choices(string.ascii_letters + string.digits, k=length))

    def _random_email(self) -> str:
        domains = ['example.com', 'test.com', 'demo.org', 'sample.net']
        return f"{self._random_string(8)}@{random.choice(domains)}"

    def _random_phone(self) -> str:
        return f"138{random.randint(10000000, 99999999)}"

    def _random_id_card(self) -> str:
        province_codes = ['110101', '310101', '440101', '330102', '510104']
        birth_date = (datetime.now() - timedelta(days=random.randint(6570, 23725))).strftime('%Y%m%d')
        sequence = f"{random.randint(100, 999)}"
        check_code = random.choice('0123456789X')
        return f"{random.choice(province_codes)}{birth_date}{sequence}{check_code}"

    def _random_bank_card(self) -> str:
        return f"{random.randint(600000, 699999)}{random.randint(1000000000, 9999999999)}"

    # ==================== 审计日志测试数据 ====================

    def build_audit_log_entry(self, action_type: str = "default") -> AuditLogTestData:
        """构建审计日志测试数据"""
        action_templates = {
            "login": ("user_login", "user_auth", {"ip": "192.168.1.100", "success": True}),
            "logout": ("user_logout", "user_auth", {"ip": "192.168.1.100"}),
            "create": ("resource_create", "resource_manager", {"resource_type": "document"}),
            "update": ("resource_update", "resource_manager", {"resource_id": "res_123"}),
            "delete": ("resource_delete", "resource_manager", {"resource_id": "res_456"}),
            "query": ("data_query", "data_service", {"table": "users", "limit": 100}),
            "export": ("data_export", "data_service", {"format": "csv", "rows": 5000}),
            "admin": ("system_admin", "system", {"operation": "config_update"}),
            "default": (f"action_{self._random_string(5)}", f"module_{self._random_string(5)}", {})
        }

        action, resource, base_details = action_templates.get(action_type, action_templates["default"])
        details = dict(base_details)
        details["timestamp"] = datetime.utcnow().isoformat()
        details["trace_id"] = self._random_string(32)

        return AuditLogTestData(
            action=action,
            actor=f"user_{random.randint(1000, 9999)}",
            resource=resource,
            details=details,
            expected_sequence=0
        )

    def build_audit_log_batch(self, count: int, action_types: Optional[List[str]] = None) -> List[AuditLogTestData]:
        """构建批量审计日志测试数据"""
        action_types = action_types or ["login", "create", "update", "query"]
        entries = []
        for i in range(count):
            entry = self.build_audit_log_entry(random.choice(action_types))
            entry.expected_sequence = i + 1
            entries.append(entry)
        return entries

    def build_tampered_audit_log(self, original: AuditLogTestData, tamper_type: str = "modify_details") -> AuditLogTestData:
        """构建被篡改的审计日志数据"""
        if tamper_type == "modify_details":
            modified = dict(original.details)
            modified["tampered"] = True
            modified["original_action"] = original.action
            return AuditLogTestData(
                action=f"tampered_{original.action}",
                actor=original.actor,
                resource=original.resource,
                details=modified,
                expected_sequence=original.expected_sequence
            )
        elif tamper_type == "change_actor":
            return AuditLogTestData(
                action=original.action,
                actor=f"hacker_{self._random_string(5)}",
                resource=original.resource,
                details=original.details,
                expected_sequence=original.expected_sequence
            )
        elif tamper_type == "change_action":
            return AuditLogTestData(
                action=f"unauthorized_{original.action}",
                actor=original.actor,
                resource=original.resource,
                details=original.details,
                expected_sequence=original.expected_sequence
            )
        return original

    # ==================== 数据脱敏测试数据 ====================

    def build_masking_test_data(self, sensitivity_level: str = "high") -> MaskingTestData:
        """构建数据脱敏测试数据"""
        base_data = {
            "username": f"test_user_{self._counter}",
            "nickname": f"用户{self._counter}",
            "email": self._random_email(),
            "phone": self._random_phone(),
            "id_card": self._random_id_card(),
            "bank_card": self._random_bank_card(),
            "age": random.randint(18, 65),
            "city": random.choice(["北京", "上海", "广州", "深圳", "杭州"]),
            "login_ip": f"192.168.{random.randint(0, 255)}.{random.randint(1, 254)}",
            "api_key": f"sk_{self._random_string(32)}",
            "password": self._random_string(16),
            "created_at": datetime.utcnow().isoformat()
        }

        level_configs = {
            "high": {
                "role": "guest",
                "expected_masked": ["email", "phone", "id_card", "bank_card", "api_key", "password", "login_ip"],
                "expected_visible": ["username", "nickname", "age", "city", "created_at"]
            },
            "medium": {
                "role": "user",
                "expected_masked": ["email", "phone", "id_card", "api_key", "password"],
                "expected_visible": ["username", "nickname", "age", "city", "bank_card", "login_ip", "created_at"]
            },
            "low": {
                "role": "admin",
                "expected_masked": [],
                "expected_visible": list(base_data.keys())
            }
        }

        config = level_configs.get(sensitivity_level, level_configs["medium"])
        return MaskingTestData(
            original_data=base_data,
            role=config["role"],
            expected_masked_fields=config["expected_masked"],
            expected_visible_fields=config["expected_visible"]
        )

    def build_masking_nested_data(self) -> MaskingTestData:
        """构建嵌套结构的脱敏测试数据"""
        nested_data = {
            "user": {
                "basic": {
                    "name": "张三",
                    "email": self._random_email(),
                    "phone": self._random_phone()
                },
                "identity": {
                    "id_card": self._random_id_card(),
                    "bank_card": self._random_bank_card()
                },
                "security": {
                    "password": self._random_string(16),
                    "api_keys": [
                        f"sk_{self._random_string(32)}",
                        f"sk_{self._random_string(32)}"
                    ]
                }
            },
            "transaction": {
                "amount": random.randint(100, 10000),
                "recipient_account": self._random_bank_card()
            }
        }

        return MaskingTestData(
            original_data=nested_data,
            role="user",
            expected_masked_fields=["user.basic.email", "user.basic.phone", "user.identity.id_card",
                                    "user.identity.bank_card", "user.security.password",
                                    "transaction.recipient_account"],
            expected_visible_fields=["user.basic.name", "transaction.amount"]
        )

    def build_masking_list_data(self, count: int = 5) -> MaskingTestData:
        """构建列表数据的脱敏测试数据"""
        users = []
        for i in range(count):
            users.append({
                "id": i + 1,
                "name": f"用户{i + 1}",
                "email": self._random_email(),
                "phone": self._random_phone(),
                "id_card": self._random_id_card()
            })

        return MaskingTestData(
            original_data={"users": users, "total": count},
            role="user",
            expected_masked_fields=["email", "phone", "id_card"],
            expected_visible_fields=["id", "name", "total"]
        )

    # ==================== 密钥分片测试数据 ====================

    def build_shamir_test_data(self, complexity: str = "standard") -> ShamirTestData:
        """构建密钥分片测试数据"""
        complexity_configs = {
            "simple": {
                "secret_length": 16,
                "threshold": 2,
                "total": 3,
                "holders": ["alice", "bob"]
            },
            "standard": {
                "secret_length": 32,
                "threshold": 3,
                "total": 5,
                "holders": ["alice", "bob", "charlie", "dave", "eve"]
            },
            "complex": {
                "secret_length": 64,
                "threshold": 5,
                "total": 10,
                "holders": [f"node_{i}" for i in range(10)]
            },
            "minimum": {
                "secret_length": 16,
                "threshold": 2,
                "total": 2,
                "holders": ["holder1", "holder2"]
            }
        }

        config = complexity_configs.get(complexity, complexity_configs["standard"])
        secret = bytes([random.randint(0, 255) for _ in range(config["secret_length"])])

        return ShamirTestData(
            secret=secret,
            threshold=config["threshold"],
            total_shares=config["total"],
            holders=config["holders"][:config["total"]]
        )

    def build_shamir_edge_cases(self) -> List[ShamirTestData]:
        """构建密钥分片的边界测试场景"""
        edge_cases = []

        # 最小阈值场景
        edge_cases.append(ShamirTestData(
            secret=b"minimal_secret_key_16",
            threshold=2,
            total_shares=2,
            holders=["node1", "node2"]
        ))

        # 高阈值场景
        edge_cases.append(ShamirTestData(
            secret=b"high_threshold_secret_key_32_bytes!!",
            threshold=8,
            total_shares=10,
            holders=[f"node_{i}" for i in range(10)]
        ))

        # 大单份额场景
        edge_cases.append(ShamirTestData(
            secret=b"many_shares_secret_32_bytes_data!!!!",
            threshold=3,
            total_shares=20,
            holders=[f"node_{i}" for i in range(20)]
        ))

        return edge_cases

    def build_shamir_recovery_scenarios(self, test_data: ShamirTestData) -> Dict[str, Any]:
        """构建密钥恢复的各种场景"""
        indices = list(range(1, test_data.total_shares + 1))
        random.shuffle(indices)

        return {
            "exact_threshold": indices[:test_data.threshold],
            "more_than_threshold": indices[:test_data.threshold + 1],
            "less_than_threshold": indices[:test_data.threshold - 1],
            "all_shares": indices,
            "random_subset": random.sample(indices, min(test_data.threshold + 2, test_data.total_shares))
        }

    # ==================== 并发测试数据 ====================

    def build_concurrent_test_data(self, operation_count: int = 100) -> Dict[str, Any]:
        """构建并发测试数据"""
        operations = []
        for i in range(operation_count):
            op_type = random.choice(["read", "write", "update", "delete"])
            operations.append({
                "id": i,
                "type": op_type,
                "data": self.build_masking_test_data("medium").original_data if op_type in ["write", "update"] else None,
                "timestamp": datetime.utcnow().isoformat()
            })

        return {
            "thread_count": min(10, operation_count // 10),
            "operations": operations,
            "expected_success_rate": 0.95
        }

    # ==================== 性能测试数据 ====================

    def build_performance_test_data(self, size: str = "medium") -> Dict[str, Any]:
        """构建性能测试数据"""
        size_configs = {
            "small": {"log_count": 100, "data_depth": 2, "list_size": 10},
            "medium": {"log_count": 1000, "data_depth": 5, "list_size": 100},
            "large": {"log_count": 10000, "data_depth": 10, "list_size": 1000}
        }
        config = size_configs.get(size, size_configs["medium"])

        return {
            "audit_logs": self.build_audit_log_batch(config["log_count"]),
            "masking_data": self.build_masking_test_data("high"),
            "shamir_data": self.build_shamir_test_data("standard"),
            "iterations": 100 if size == "small" else 10
        }


# 全局测试数据构建器实例
_test_data_builder = None


def get_test_data_builder(seed: int = 42) -> DataBuilder:
    """获取测试数据构建器单例"""
    global _test_data_builder
    if _test_data_builder is None:
        _test_data_builder = DataBuilder(seed)
    return _test_data_builder


def reset_test_data_builder(seed: int = 42) -> DataBuilder:
    """重置测试数据构建器"""
    global _test_data_builder
    _test_data_builder = DataBuilder(seed)
    return _test_data_builder
