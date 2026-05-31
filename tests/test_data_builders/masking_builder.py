"""动态数据脱敏模块测试数据构建器"""

from dataclasses import dataclass, field
from datetime import datetime, timezone
from enum import Enum
from typing import Any, Dict, List, Optional
from uuid import uuid4


class UserRole(str, Enum):
    Admin = "Admin"
    Manager = "Manager"
    Operator = "Operator"
    Viewer = "Viewer"
    Guest = "Guest"


class MaskingStrategy(str, Enum):
    Full = "Full"
    Partial = "Partial"
    Hash = "Hash"
    Replace = "Replace"
    Redact = "Redact"
    None_ = "None"


@dataclass
class MaskingRule:
    field_name: str
    data_type: str
    strategy: MaskingStrategy
    visible_chars: int
    required_role: int
    pattern: Optional[str] = None


@dataclass
class MaskingContext:
    user_id: str
    user_role: UserRole
    request_id: str
    timestamp: datetime
    additional_claims: Dict[str, Any] = field(default_factory=dict)


@dataclass
class MaskingResult:
    original_value: str
    masked_value: str
    strategy_used: MaskingStrategy
    is_masked: bool


@dataclass
class MaskingConfig:
    enabled: bool = True
    mask_email: bool = True
    mask_phone: bool = True
    mask_id_card: bool = True
    default_mask_char: str = "*"


class MaskingTestDataBuilder:
    """动态数据脱敏模块测试数据构建器
    
    用于构建基于用户权限动态脱敏敏感字段、保持数据可用性相关的测试数据。
    """
    
    def __init__(self):
        self._user_counter = 0
    
    def _generate_id(self, prefix: str) -> str:
        """生成唯一ID"""
        return f"{prefix}_{uuid4().hex[:12]}"
    
    def get_role_permission_level(self, role: UserRole) -> int:
        """获取角色权限等级（越小权限越高）"""
        levels = {
            UserRole.Admin: 0,
            UserRole.Manager: 1,
            UserRole.Operator: 2,
            UserRole.Viewer: 3,
            UserRole.Guest: 4,
        }
        return levels.get(role, 4)
    
    def can_view_field(self, role: UserRole, required_role_level: int) -> bool:
        """判断角色是否有权限查看字段"""
        return self.get_role_permission_level(role) <= required_role_level
    
    def build_context(
        self,
        user_role: UserRole = UserRole.Viewer,
        user_id: Optional[str] = None,
        additional_claims: Optional[Dict[str, Any]] = None,
    ) -> MaskingContext:
        """构建脱敏上下文
        
        Args:
            user_role: 用户角色
            user_id: 用户ID
            additional_claims: 额外声明
        """
        self._user_counter += 1
        return MaskingContext(
            user_id=user_id or f"user_{self._user_counter:03d}",
            user_role=user_role,
            request_id=self._generate_id("req"),
            timestamp=datetime.now(timezone.utc),
            additional_claims=additional_claims or {},
        )
    
    def build_rule(
        self,
        field_name: str,
        data_type: str = "text",
        strategy: MaskingStrategy = MaskingStrategy.Partial,
        visible_chars: int = 2,
        required_role_level: int = 1,
        pattern: Optional[str] = None,
    ) -> MaskingRule:
        """构建脱敏规则
        
        Args:
            field_name: 字段名
            data_type: 数据类型
            strategy: 脱敏策略
            visible_chars: 可见字符数
            required_role_level: 需要的角色等级
            pattern: 匹配模式（正则）
        """
        return MaskingRule(
            field_name=field_name,
            data_type=data_type,
            strategy=strategy,
            visible_chars=visible_chars,
            required_role=required_role_level,
            pattern=pattern,
        )
    
    def build_email_rule(self, required_role_level: int = 1) -> MaskingRule:
        """构建邮箱脱敏规则"""
        return MaskingRule(
            field_name="email",
            data_type="email",
            strategy=MaskingStrategy.Partial,
            visible_chars=2,
            required_role=required_role_level,
            pattern=r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}",
        )
    
    def build_phone_rule(self, required_role_level: int = 1) -> MaskingRule:
        """构建手机号脱敏规则"""
        return MaskingRule(
            field_name="phone",
            data_type="phone",
            strategy=MaskingStrategy.Partial,
            visible_chars=3,
            required_role=required_role_level,
            pattern=r"1[3-9]\d{9}",
        )
    
    def build_id_card_rule(self, required_role_level: int = 0) -> MaskingRule:
        """构建身份证脱敏规则"""
        return MaskingRule(
            field_name="id_card",
            data_type="id_card",
            strategy=MaskingStrategy.Partial,
            visible_chars=4,
            required_role=required_role_level,
            pattern=r"[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]",
        )
    
    def build_password_rule(self) -> MaskingRule:
        """构建密码脱敏规则"""
        return MaskingRule(
            field_name="password",
            data_type="password",
            strategy=MaskingStrategy.Full,
            visible_chars=0,
            required_role=0,
        )
    
    def build_credit_card_rule(self, required_role_level: int = 0) -> MaskingRule:
        """构建信用卡脱敏规则"""
        return MaskingRule(
            field_name="credit_card",
            data_type="credit_card",
            strategy=MaskingStrategy.Partial,
            visible_chars=4,
            required_role=required_role_level,
            pattern=r"\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}",
        )
    
    def build_test_emails(self) -> List[str]:
        """构建测试邮箱列表"""
        return [
            "user@example.com",
            "admin@company.org",
            "test.user+tag@subdomain.co.uk",
            "a@b.cn",
            "very_long_email_address_with_many_chars@domain.com",
        ]
    
    def build_test_phones(self) -> List[str]:
        """构建测试手机号列表"""
        return [
            "13800138000",
            "15912345678",
            "18688888888",
            "17700001111",
            "19999999999",
        ]
    
    def build_test_id_cards(self) -> List[str]:
        """构建测试身份证列表"""
        return [
            "110101199001011234",
            "310101198505056789",
            "44010119951212001X",
            "510101198003035678",
        ]
    
    def build_test_credit_cards(self) -> List[str]:
        """构建测试信用卡列表"""
        return [
            "4111111111111111",
            "5555555555554444",
            "378282246310005",
            "6011111111111117",
            "4111-1111-1111-1111",
        ]
    
    def build_test_passwords(self) -> List[str]:
        """构建测试密码列表"""
        return [
            "password123",
            "MySecretP@ss!",
            "12345678",
            "!@#$%^&*()",
            "a" * 32,
        ]
    
    def build_test_names(self) -> List[str]:
        """构建测试姓名列表"""
        return [
            "张三",
            "李四",
            "王小明",
            "欧阳锋",
            "John Smith",
        ]
    
    def build_test_addresses(self) -> List[str]:
        """构建测试地址列表"""
        return [
            "北京市朝阳区建国路88号SOHO现代城",
            "上海市浦东新区陆家嘴金融中心",
            "广东省深圳市南山区科技园",
            "浙江省杭州市西湖区文三路",
        ]
    
    def build_edge_case_values(self) -> List[str]:
        """构建边界情况值"""
        return [
            "",
            "a",
            "ab",
            "abc",
            " " * 10,
            "\n\r\t",
            "x" * 100,
            "中文测试" * 20,
            "!@#$%^&*()_+-=[]{}|;':\",./<>?",
        ]
    
    def build_user_contexts(self) -> List[tuple[UserRole, MaskingContext]]:
        """构建所有角色的上下文"""
        contexts = []
        for role in UserRole:
            contexts.append((role, self.build_context(user_role=role)))
        return contexts
    
    def build_test_json_data(self) -> Dict[str, Any]:
        """构建测试JSON数据"""
        return {
            "id": 1,
            "name": "张三",
            "email": "zhangsan@example.com",
            "phone": "13800138000",
            "id_card": "110101199001011234",
            "credit_card": "4111111111111111",
            "password": "secret123",
            "address": "北京市朝阳区建国路88号",
            "age": 30,
            "active": True,
            "nested": {
                "contact": {
                    "email": "secondary@example.com",
                    "phone": "15912345678",
                }
            },
            "list": [
                {"email": "user1@example.com"},
                {"email": "user2@example.com"},
            ],
        }
    
    def build_test_text_with_sensitive_data(self) -> str:
        """构建包含敏感数据的测试文本"""
        return (
            "联系人信息：\n"
            "姓名：张三\n"
            "邮箱：zhangsan@example.com\n"
            "手机：13800138000\n"
            "身份证：110101199001011234\n"
            "信用卡：4111111111111111\n"
            "地址：北京市朝阳区建国路88号\n"
        )
    
    def build_batch_masking_fields(self) -> Dict[str, str]:
        """构建批量脱敏字段"""
        return {
            "email": "admin@company.org",
            "phone": "13800138000",
            "name": "李四",
            "id_card": "310101198505056789",
        }
    
    def build_invalid_field_values(self) -> List[tuple[str, str]]:
        """构建无效字段值列表（用于参数校验）"""
        return [
            ("email", "not_an_email"),
            ("email", "@missing_local.com"),
            ("email", "missing_domain@"),
            ("phone", "12345"),
            ("phone", "123456789012"),
            ("phone", "23800138000"),
            ("id_card", "12345"),
            ("id_card", "11010119900101123"),
            ("id_card", "1101011990010112345"),
            ("credit_card", "1234"),
            ("credit_card", "12345678901234567890"),
        ]
    
    def build_all_roles(self) -> List[UserRole]:
        """构建所有角色"""
        return list(UserRole)
    
    def build_role_hierarchy(self) -> Dict[str, List[UserRole]]:
        """构建角色层级关系"""
        return {
            "can_view_id_card": [UserRole.Admin],
            "can_view_credit_card": [UserRole.Admin],
            "can_view_email": [UserRole.Admin, UserRole.Manager],
            "can_view_phone": [UserRole.Admin, UserRole.Manager],
            "can_view_name": [UserRole.Admin, UserRole.Manager, UserRole.Operator],
            "can_view_address": [UserRole.Admin, UserRole.Manager, UserRole.Operator],
        }
    
    def build_config(self, **overrides) -> MaskingConfig:
        """构建脱敏配置
        
        Args:
            **overrides: 覆盖默认配置的参数
        """
        config = MaskingConfig()
        for key, value in overrides.items():
            if hasattr(config, key):
                setattr(config, key, value)
        return config
