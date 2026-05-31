"""
基础测试数据构建器
提供通用的测试数据构造能力
"""
from abc import ABC, abstractmethod
from typing import Any, Dict, Generic, List, Optional, TypeVar
from faker import Faker
import uuid
import random
from datetime import datetime, timedelta

T = TypeVar('T')


class BaseTestDataBuilder(ABC, Generic[T]):
    """
    抽象基类，所有测试数据构建器都继承自此类
    遵循Builder设计模式，提供流式API
    """

    _fake = Faker('zh_CN')
    _faker = Faker('zh_CN')

    def __init__(self):
        self._data: Dict[str, Any] = {}
        self._reset()

    @abstractmethod
    def _reset(self) -> None:
        """重置构建器到初始状态"""
        pass

    @abstractmethod
    def build(self) -> T:
        """构建最终的测试数据"""
        pass

    def with_field(self, field_name: str, value: Any) -> 'BaseTestDataBuilder':
        """通用方法：设置任意字段的值"""
        self._data[field_name] = value
        return self

    def with_random_id(self, prefix: str = "") -> 'BaseTestDataBuilder':
        """生成随机ID"""
        self._data['id'] = f"{prefix}{uuid.uuid4().hex[:8]}"
        return self

    def with_random_string(self, field_name: str, length: int = 10) -> 'BaseTestDataBuilder':
        """生成随机字符串"""
        self._data[field_name] = self._fake.pystr(min_chars=length, max_chars=length)
        return self

    def with_random_email(self) -> 'BaseTestDataBuilder':
        """生成随机邮箱"""
        self._data['email'] = self._fake.email()
        return self

    def with_random_phone(self) -> 'BaseTestDataBuilder':
        """生成随机手机号"""
        self._data['phone'] = self._fake.phone_number()
        return self

    def with_random_datetime(self, field_name: str, within_days: int = 30) -> 'BaseTestDataBuilder':
        """生成指定范围内的随机日期时间"""
        end = datetime.now()
        start = end - timedelta(days=within_days)
        self._data[field_name] = self._fake.date_time_between(start_date=start, end_date=end)
        return self

    def with_random_choice(self, field_name: str, choices: List[Any]) -> 'BaseTestDataBuilder':
        """从列表中随机选择"""
        self._data[field_name] = random.choice(choices)
        return self

    def with_random_int(self, field_name: str, min_val: int = 0, max_val: int = 100) -> 'BaseTestDataBuilder':
        """生成随机整数"""
        self._data[field_name] = random.randint(min_val, max_val)
        return self

    def with_random_float(self, field_name: str, min_val: float = 0.0, max_val: float = 100.0) -> 'BaseTestDataBuilder':
        """生成随机浮点数"""
        self._data[field_name] = random.uniform(min_val, max_val)
        return self

    def reset(self) -> 'BaseTestDataBuilder':
        """公共重置方法"""
        self._reset()
        return self

    @staticmethod
    def generate_id(prefix: str = "") -> str:
        """静态方法：生成ID"""
        return f"{prefix}{uuid.uuid4().hex[:8]}"

    @staticmethod
    def generate_user_id() -> str:
        """生成用户ID"""
        return f"user_{uuid.uuid4().hex[:8]}"

    @staticmethod
    def generate_trace_id() -> str:
        """生成追踪ID"""
        return f"trace_{uuid.uuid4().hex[:16]}"
