import re
from typing import Any, Callable, Dict, List, Optional, Set
from pydantic import BaseModel, Field


class MaskingRule(BaseModel):
    field_name: str = Field(..., description="字段名")
    rule_type: str = Field(..., description="规则类型: mask, hash, truncate, replace")
    pattern: Optional[str] = Field(None, description="正则模式")
    replacement: Optional[str] = Field(None, description="替换字符")
    visible_start: Optional[int] = Field(None, description="显示前N位")
    visible_end: Optional[int] = Field(None, description="显示后N位")
    hash_algorithm: Optional[str] = Field("sha256", description="哈希算法")


class UserRole(BaseModel):
    role_name: str = Field(..., description="角色名")
    allowed_fields: Set[str] = Field(default_factory=set, description="允许查看的字段")
    masked_fields: Dict[str, MaskingRule] = Field(default_factory=dict, description="脱敏规则")


class DataMaskingEngine:
    def __init__(self):
        self.roles: Dict[str, UserRole] = {}
        self._init_default_roles()
        self._init_default_patterns()

    def _init_default_patterns(self):
        self.patterns = {
            "email": re.compile(r"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$"),
            "phone": re.compile(r"^1[3-9]\d{9}$"),
            "id_card": re.compile(r"^\d{17}[\dXx]$"),
            "bank_card": re.compile(r"^\d{16,19}$"),
            "ipv4": re.compile(r"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$")
        }

    def _init_default_roles(self):
        self.roles["admin"] = UserRole(
            role_name="admin",
            allowed_fields=set(),
            masked_fields={}
        )
        self.roles["user"] = UserRole(
            role_name="user",
            allowed_fields=set(),
            masked_fields={
                "email": MaskingRule(field_name="email", rule_type="mask", visible_start=2, visible_end=2),
                "phone": MaskingRule(field_name="phone", rule_type="mask", visible_start=3, visible_end=4),
                "id_card": MaskingRule(field_name="id_card", rule_type="mask", visible_start=6, visible_end=4),
                "password": MaskingRule(field_name="password", rule_type="replace", replacement="***"),
                "api_key": MaskingRule(field_name="api_key", rule_type="mask", visible_start=3, visible_end=3)
            }
        )
        self.roles["guest"] = UserRole(
            role_name="guest",
            allowed_fields=set(),
            masked_fields={
                "email": MaskingRule(field_name="email", rule_type="replace", replacement="***@***.com"),
                "phone": MaskingRule(field_name="phone", rule_type="replace", replacement="***-****-****"),
                "name": MaskingRule(field_name="name", rule_type="replace", replacement="***"),
                "id_card": MaskingRule(field_name="id_card", rule_type="replace", replacement="**********"),
                "bank_card": MaskingRule(field_name="bank_card", rule_type="replace", replacement="**** **** **** ****"),
                "api_key": MaskingRule(field_name="api_key", rule_type="replace", replacement="sk_********"),
                "password": MaskingRule(field_name="password", rule_type="replace", replacement="***"),
                "login_ip": MaskingRule(field_name="login_ip", rule_type="replace", replacement="***.***.***.***")
            }
        )

    def add_role(self, role: UserRole) -> None:
        self.roles[role.role_name] = role

    def remove_role(self, role_name: str) -> None:
        if role_name in self.roles:
            del self.roles[role_name]

    def add_masking_rule(self, role_name: str, rule: MaskingRule) -> bool:
        if role_name not in self.roles:
            return False
        self.roles[role_name].masked_fields[rule.field_name] = rule
        return True

    def remove_masking_rule(self, role_name: str, field_name: str) -> bool:
        if role_name not in self.roles:
            return False
        if field_name in self.roles[role_name].masked_fields:
            del self.roles[role_name].masked_fields[field_name]
            return True
        return False

    def _mask_value(self, value: Any, rule: MaskingRule) -> Any:
        if value is None:
            return None

        value_str = str(value)

        if rule.rule_type == "mask":
            start = rule.visible_start or 0
            end = rule.visible_end or 0
            if len(value_str) <= start + end:
                return value_str
            masked_part = "*" * (len(value_str) - start - end)
            return value_str[:start] + masked_part + value_str[-end:] if end > 0 else value_str[:start] + masked_part

        elif rule.rule_type == "replace":
            return rule.replacement or "***"

        elif rule.rule_type == "hash":
            from .utils import hash_data
            return hash_data(value_str, rule.hash_algorithm or "sha256")

        elif rule.rule_type == "truncate":
            max_len = rule.visible_start or 10
            if len(value_str) <= max_len:
                return value_str
            return value_str[:max_len] + "..."

        return value_str

    def _mask_nested(self, data: Any, rules: Dict[str, MaskingRule], path: str = "") -> Any:
        if isinstance(data, dict):
            result = {}
            for key, value in data.items():
                current_path = f"{path}.{key}" if path else key
                if current_path in rules:
                    result[key] = self._mask_value(value, rules[current_path])
                elif key in rules:
                    result[key] = self._mask_value(value, rules[key])
                else:
                    result[key] = self._mask_nested(value, rules, current_path)
            return result

        elif isinstance(data, list):
            return [self._mask_nested(item, rules, path) for item in data]

        return data

    def mask_data(self, data: Any, role_name: str) -> Any:
        if role_name not in self.roles:
            role_name = "guest"

        role = self.roles[role_name]

        if not role.masked_fields:
            return data

        return self._mask_nested(data, role.masked_fields)

    def auto_detect_and_mask(self, data: Any, role_name: str) -> Any:
        masked_data = self.mask_data(data, role_name)
        return self._auto_mask_by_pattern(masked_data)

    def _auto_mask_by_pattern(self, data: Any) -> Any:
        if isinstance(data, dict):
            result = {}
            for key, value in data.items():
                result[key] = self._auto_mask_by_pattern(value)
            return result
        elif isinstance(data, list):
            return [self._auto_mask_by_pattern(item) for item in data]
        elif isinstance(data, str):
            if self.patterns["id_card"].match(data):
                return data[:6] + "********" + data[-4:]
            elif self.patterns["phone"].match(data):
                return data[:3] + "****" + data[-4:]
            elif self.patterns["email"].match(data):
                parts = data.split("@")
                return parts[0][:2] + "***@" + parts[1]
            elif self.patterns["bank_card"].match(data):
                return data[:4] + " **** **** " + data[-4:]
        return data

    def get_available_roles(self) -> List[str]:
        return list(self.roles.keys())

    def get_role_rules(self, role_name: str) -> Optional[Dict[str, Any]]:
        if role_name not in self.roles:
            return None
        role = self.roles[role_name]
        return {
            "role_name": role.role_name,
            "masked_fields": {k: v.dict() for k, v in role.masked_fields.items()}
        }


_masking_engine_instance: Optional[DataMaskingEngine] = None


def get_masking_engine() -> DataMaskingEngine:
    global _masking_engine_instance
    if _masking_engine_instance is None:
        _masking_engine_instance = DataMaskingEngine()
    return _masking_engine_instance
