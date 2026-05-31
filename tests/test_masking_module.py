"""动态数据脱敏模块单元测试

测试重点：参数校验完备性
- 用户角色参数校验
- 字段名参数校验
- 脱敏值参数校验
- 策略参数校验
- 边界情况的参数处理
"""

import pytest
from unittest.mock import MagicMock, patch
from typing import Dict, List

from tests.test_data_builders.masking_builder import (
    MaskingTestDataBuilder,
    UserRole,
    MaskingStrategy,
    MaskingRule,
    MaskingContext,
    MaskingConfig,
)


@pytest.fixture
def masking_builder():
    """脱敏模块测试数据构建器fixture"""
    return MaskingTestDataBuilder()


@pytest.fixture
def masking_config():
    """脱敏配置fixture"""
    return MaskingConfig()


class TestUserRoleValidation:
    """用户角色参数校验测试"""
    
    def test_valid_roles(self, masking_builder):
        """测试：所有有效角色"""
        roles = masking_builder.build_all_roles()
        
        expected_roles = {"Admin", "Manager", "Operator", "Viewer", "Guest"}
        actual_roles = {role.value for role in roles}
        
        assert expected_roles == actual_roles
        assert len(roles) == 5
    
    def test_role_permission_levels(self, masking_builder):
        """测试：角色权限等级"""
        roles = masking_builder.build_all_roles()
        
        levels = {}
        for role in roles:
            levels[role.value] = masking_builder.get_role_permission_level(role)
        
        assert levels["Admin"] == 0
        assert levels["Manager"] == 1
        assert levels["Operator"] == 2
        assert levels["Viewer"] == 3
        assert levels["Guest"] == 4
    
    def test_role_hierarchy(self, masking_builder):
        """测试：角色层级关系"""
        hierarchy = masking_builder.build_role_hierarchy()
        
        assert "can_view_id_card" in hierarchy
        assert "can_view_credit_card" in hierarchy
        assert "can_view_email" in hierarchy
        assert "can_view_phone" in hierarchy
        assert "can_view_name" in hierarchy
        
        assert UserRole.Admin in hierarchy["can_view_id_card"]
        assert UserRole.Admin not in hierarchy["can_view_name"] or len(hierarchy["can_view_name"]) > 1


class TestContextValidation:
    """脱敏上下文参数校验测试"""
    
    def test_context_creation(self, masking_builder):
        """测试：正常创建上下文"""
        context = masking_builder.build_context(user_role=UserRole.Admin)
        
        assert context.user_id.startswith("user_")
        assert context.user_role == UserRole.Admin
        assert context.request_id.startswith("req_")
        assert context.additional_claims == {}
    
    def test_context_with_additional_claims(self, masking_builder):
        """测试：上下文带额外声明"""
        claims = {
            "department": "engineering",
            "clearance": "top_secret",
            "expires_at": "2026-12-31"
        }
        context = masking_builder.build_context(
            user_role=UserRole.Manager,
            additional_claims=claims
        )
        
        assert context.additional_claims == claims
        assert context.additional_claims["department"] == "engineering"
    
    def test_context_all_roles(self, masking_builder):
        """测试：所有角色的上下文"""
        role_contexts = masking_builder.build_user_contexts()
        
        assert len(role_contexts) == 5
        
        for role, context in role_contexts:
            assert context.user_role == role
            assert context.user_id is not None


class TestRuleValidation:
    """脱敏规则参数校验测试"""
    
    def test_create_rule_with_defaults(self, masking_builder):
        """测试：创建默认规则"""
        rule = masking_builder.build_rule(field_name="test_field")
        
        assert rule.field_name == "test_field"
        assert rule.data_type == "text"
        assert rule.strategy == MaskingStrategy.Partial
        assert rule.visible_chars == 2
        assert rule.required_role == 1
        assert rule.pattern is None
    
    def test_create_rule_with_custom_parameters(self, masking_builder):
        """测试：创建自定义规则"""
        rule = masking_builder.build_rule(
            field_name="custom_field",
            data_type="custom",
            strategy=MaskingStrategy.Full,
            visible_chars=0,
            required_role_level=0,
            pattern=r"^\d+$"
        )
        
        assert rule.field_name == "custom_field"
        assert rule.data_type == "custom"
        assert rule.strategy == MaskingStrategy.Full
        assert rule.visible_chars == 0
        assert rule.required_role == 0
        assert rule.pattern == r"^\d+$"
    
    def test_email_rule(self, masking_builder):
        """测试：邮箱规则"""
        rule = masking_builder.build_email_rule()
        
        assert rule.field_name == "email"
        assert rule.data_type == "email"
        assert rule.strategy == MaskingStrategy.Partial
        assert rule.pattern is not None
        assert "@" in rule.pattern
    
    def test_phone_rule(self, masking_builder):
        """测试：手机号规则"""
        rule = masking_builder.build_phone_rule()
        
        assert rule.field_name == "phone"
        assert rule.data_type == "phone"
        assert rule.strategy == MaskingStrategy.Partial
        assert rule.visible_chars == 3
        assert rule.pattern is not None
    
    def test_id_card_rule(self, masking_builder):
        """测试：身份证规则"""
        rule = masking_builder.build_id_card_rule()
        
        assert rule.field_name == "id_card"
        assert rule.data_type == "id_card"
        assert rule.required_role == 0
        assert rule.pattern is not None
    
    def test_password_rule(self, masking_builder):
        """测试：密码规则"""
        rule = masking_builder.build_password_rule()
        
        assert rule.field_name == "password"
        assert rule.data_type == "password"
        assert rule.strategy == MaskingStrategy.Full
        assert rule.visible_chars == 0
        assert rule.required_role == 0
    
    def test_credit_card_rule(self, masking_builder):
        """测试：信用卡规则"""
        rule = masking_builder.build_credit_card_rule()
        
        assert rule.field_name == "credit_card"
        assert rule.data_type == "credit_card"
        assert rule.visible_chars == 4
        assert rule.pattern is not None


class TestEmailValidation:
    """邮箱参数校验测试"""
    
    def test_valid_emails(self, masking_builder):
        """测试：有效邮箱"""
        emails = masking_builder.build_test_emails()
        
        assert len(emails) == 5
        for email in emails:
            assert "@" in email
            assert "." in email.split("@")[-1]
    
    def test_email_with_special_chars(self, masking_builder):
        """测试：带特殊字符的邮箱"""
        special_emails = [
            "user+tag@example.com",
            "user.name@sub.domain.org",
            "a@b.cn",
        ]
        
        for email in special_emails:
            assert "@" in email
    
    def test_long_email(self, masking_builder):
        """测试边界条件：超长邮箱"""
        long_email = "a" * 64 + "@" + "b" * 253 + ".com"
        
        assert len(long_email) > 300
        assert "@" in long_email


class TestPhoneValidation:
    """手机号参数校验测试"""
    
    def test_valid_phones(self, masking_builder):
        """测试：有效手机号"""
        phones = masking_builder.build_test_phones()
        
        assert len(phones) == 5
        for phone in phones:
            assert len(phone) == 11
            assert phone.isdigit()
            assert phone[0] == "1"
            assert phone[1] in "3456789"


class TestIdCardValidation:
    """身份证参数校验测试"""
    
    def test_valid_id_cards(self, masking_builder):
        """测试：有效身份证"""
        id_cards = masking_builder.build_test_id_cards()
        
        assert len(id_cards) == 4
        for id_card in id_cards:
            assert len(id_card) == 18 or (len(id_card) == 15)
            assert id_card[:17].isdigit()


class TestCreditCardValidation:
    """信用卡参数校验测试"""
    
    def test_valid_credit_cards(self, masking_builder):
        """测试：有效信用卡号"""
        credit_cards = masking_builder.build_test_credit_cards()
        
        assert len(credit_cards) == 5
        for card in credit_cards:
            cleaned = card.replace("-", "").replace(" ", "")
            assert len(cleaned) >= 13
            assert len(cleaned) <= 19


class TestPasswordValidation:
    """密码参数校验测试"""
    
    def test_valid_passwords(self, masking_builder):
        """测试：各种密码格式"""
        passwords = masking_builder.build_test_passwords()
        
        assert len(passwords) == 5
        for pwd in passwords:
            assert len(pwd) > 0


class TestNameValidation:
    """姓名参数校验测试"""
    
    def test_valid_names(self, masking_builder):
        """测试：各种姓名格式"""
        names = masking_builder.build_test_names()
        
        assert len(names) == 5
        for name in names:
            assert len(name) >= 1


class TestAddressValidation:
    """地址参数校验测试"""
    
    def test_valid_addresses(self, masking_builder):
        """测试：各种地址格式"""
        addresses = masking_builder.build_test_addresses()
        
        assert len(addresses) == 4
        for address in addresses:
            assert len(address) > 0


class TestEdgeCaseValues:
    """边界情况参数校验测试"""
    
    def test_edge_case_values(self, masking_builder):
        """测试：所有边界情况值"""
        edge_values = masking_builder.build_edge_case_values()
        
        assert len(edge_values) == 9
        
        expected_edge_cases = [
            "",
            "a",
            "ab",
            "abc",
            " " * 10,
            "\n\r\t",
            "x" * 100,
        ]
        
        for value in edge_values:
            assert isinstance(value, str)
    
    def test_empty_string(self, masking_builder):
        """测试边界条件：空字符串"""
        edge_values = masking_builder.build_edge_case_values()
        assert "" in edge_values
    
    def test_single_char_string(self, masking_builder):
        """测试边界条件：单字符字符串"""
        edge_values = masking_builder.build_edge_case_values()
        assert "a" in edge_values
    
    def test_whitespace_string(self, masking_builder):
        """测试边界条件：全空白字符串"""
        edge_values = masking_builder.build_edge_case_values()
        assert " " * 10 in edge_values
    
    def test_control_chars_string(self, masking_builder):
        """测试边界条件：控制字符"""
        edge_values = masking_builder.build_edge_case_values()
        assert "\n\r\t" in edge_values


class TestInvalidFieldValues:
    """无效字段值参数校验测试"""
    
    def test_invalid_field_values(self, masking_builder):
        """测试：无效字段值"""
        invalid_values = masking_builder.build_invalid_field_values()
        
        assert len(invalid_values) > 0
        
        for field_name, value in invalid_values:
            assert isinstance(field_name, str)
            assert isinstance(value, str)
    
    def test_invalid_emails(self, masking_builder):
        """测试边界条件：无效邮箱"""
        invalid_values = masking_builder.build_invalid_field_values()
        
        invalid_emails = [v for f, v in invalid_values if f == "email"]
        
        for email in invalid_emails:
            assert "@" not in email or email.startswith("@") or email.endswith("@")
    
    def test_invalid_phones(self, masking_builder):
        """测试边界条件：无效手机号"""
        invalid_values = masking_builder.build_invalid_field_values()
        
        invalid_phones = [v for f, v in invalid_values if f == "phone"]
        
        for phone in invalid_phones:
            assert len(phone) != 11 or not phone.isdigit() or phone[0] != "1"
    
    def test_invalid_id_cards(self, masking_builder):
        """测试边界条件：无效身份证"""
        invalid_values = masking_builder.build_invalid_field_values()
        
        invalid_ids = [v for f, v in invalid_values if f == "id_card"]
        
        for id_card in invalid_ids:
            assert len(id_card) != 18
    
    def test_invalid_credit_cards(self, masking_builder):
        """测试边界条件：无效信用卡号"""
        invalid_values = masking_builder.build_invalid_field_values()
        
        invalid_cards = [v for f, v in invalid_values if f == "credit_card"]
        
        for card in invalid_cards:
            assert len(card) < 13 or len(card) > 19


class TestRoleBasedAccessControl:
    """基于角色的访问控制测试"""
    
    def test_admin_can_view_all_fields(self, masking_builder):
        """测试：Admin可以查看所有字段"""
        admin_context = masking_builder.build_context(user_role=UserRole.Admin)
        
        admin_level = masking_builder.get_role_permission_level(UserRole.Admin)
        
        required_levels = [0, 1, 2, 3, 4]
        for level in required_levels:
            can_view = masking_builder.can_view_field(admin_context.user_role, level)
            assert can_view == True
    
    def test_manager_can_view_manager_level_fields(self, masking_builder):
        """测试：Manager可以查看Manager及以下等级字段"""
        manager_context = masking_builder.build_context(user_role=UserRole.Manager)
        
        manager_level = masking_builder.get_role_permission_level(UserRole.Manager)
        
        assert masking_builder.can_view_field(manager_context.user_role, 1) == True
        assert masking_builder.can_view_field(manager_context.user_role, 2) == True
        assert masking_builder.can_view_field(manager_context.user_role, 0) == False
    
    def test_operator_can_view_operator_level_fields(self, masking_builder):
        """测试：Operator可以查看Operator及以下等级字段"""
        operator_context = masking_builder.build_context(user_role=UserRole.Operator)
        
        assert masking_builder.can_view_field(operator_context.user_role, 2) == True
        assert masking_builder.can_view_field(operator_context.user_role, 3) == True
        assert masking_builder.can_view_field(operator_context.user_role, 1) == False
    
    def test_viewer_can_view_only_viewer_level_fields(self, masking_builder):
        """测试：Viewer只能查看Viewer等级字段"""
        viewer_context = masking_builder.build_context(user_role=UserRole.Viewer)
        
        assert masking_builder.can_view_field(viewer_context.user_role, 3) == True
        assert masking_builder.can_view_field(viewer_context.user_role, 2) == False
    
    def test_guest_cannot_view_restricted_fields(self, masking_builder):
        """测试：Guest不能查看受限制字段"""
        guest_context = masking_builder.build_context(user_role=UserRole.Guest)
        
        guest_level = masking_builder.get_role_permission_level(UserRole.Guest)
        
        assert masking_builder.can_view_field(guest_context.user_role, 3) == False
        assert masking_builder.can_view_field(guest_context.user_role, 4) == True


class TestMaskingStrategies:
    """脱敏策略参数校验测试"""
    
    def test_all_strategies_exist(self):
        """测试：所有脱敏策略存在"""
        strategies = list(MaskingStrategy)
        
        expected_strategies = {"Full", "Partial", "Hash", "Replace", "Redact", "None"}
        actual_strategies = {s.value for s in strategies}
        
        assert expected_strategies <= actual_strategies
    
    def test_partial_strategy_visible_chars(self, masking_builder):
        """测试：Partial策略的可见字符参数"""
        visible_chars_values = [0, 1, 2, 3, 4, 5, 10]
        
        for vc in visible_chars_values:
            rule = masking_builder.build_rule(
                field_name="test",
                visible_chars=vc,
                strategy=MaskingStrategy.Partial
            )
            assert rule.visible_chars == vc
    
    def test_full_strategy_ignores_visible_chars(self, masking_builder):
        """测试：Full策略忽略可见字符参数"""
        rule = masking_builder.build_rule(
            field_name="password",
            strategy=MaskingStrategy.Full,
            visible_chars=10
        )
        
        assert rule.strategy == MaskingStrategy.Full


class TestJsonDataValidation:
    """JSON数据参数校验测试"""
    
    def test_json_data_structure(self, masking_builder):
        """测试：JSON数据结构"""
        data = masking_builder.build_test_json_data()
        
        assert "id" in data
        assert "name" in data
        assert "email" in data
        assert "phone" in data
        assert "id_card" in data
        assert "credit_card" in data
        assert "password" in data
        assert "address" in data
        assert "nested" in data
        assert "list" in data
    
    def test_json_nested_data(self, masking_builder):
        """测试：JSON嵌套数据"""
        data = masking_builder.build_test_json_data()
        
        assert isinstance(data["nested"], dict)
        assert "contact" in data["nested"]
        assert "email" in data["nested"]["contact"]
        assert "phone" in data["nested"]["contact"]
    
    def test_json_array_data(self, masking_builder):
        """测试：JSON数组数据"""
        data = masking_builder.build_test_json_data()
        
        assert isinstance(data["list"], list)
        assert len(data["list"]) == 2
        assert "email" in data["list"][0]


class TestTextWithSensitiveData:
    """包含敏感数据的文本测试"""
    
    def test_text_structure(self, masking_builder):
        """测试：包含敏感数据的文本结构"""
        text = masking_builder.build_test_text_with_sensitive_data()
        
        assert "姓名" in text
        assert "邮箱" in text
        assert "手机" in text
        assert "身份证" in text
        assert "信用卡" in text
        assert "地址" in text
    
    def test_text_contains_sensitive_patterns(self, masking_builder):
        """测试：文本包含敏感数据模式"""
        import re
        
        text = masking_builder.build_test_text_with_sensitive_data()
        
        email_pattern = r"[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}"
        phone_pattern = r"1[3-9]\d{9}"
        id_card_pattern = r"[1-9]\d{5}(18|19|20)\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\d|3[01])\d{3}[\dXx]"
        credit_card_pattern = r"\d{4}[- ]?\d{4}[- ]?\d{4}[- ]?\d{4}"
        
        assert re.search(email_pattern, text) is not None
        assert re.search(phone_pattern, text) is not None
        assert re.search(id_card_pattern, text) is not None
        assert re.search(credit_card_pattern, text) is not None


class TestBatchMaskingValidation:
    """批量脱敏参数校验测试"""
    
    def test_batch_fields_structure(self, masking_builder):
        """测试：批量脱敏字段结构"""
        fields = masking_builder.build_batch_masking_fields()
        
        assert len(fields) == 4
        assert "email" in fields
        assert "phone" in fields
        assert "name" in fields
        assert "id_card" in fields
        
        for field_name, value in fields.items():
            assert isinstance(field_name, str)
            assert isinstance(value, str)
            assert len(value) > 0


class TestMaskingConfigValidation:
    """脱敏配置参数校验测试"""
    
    def test_default_config_values(self, masking_config):
        """测试：默认配置值"""
        assert masking_config.enabled == True
        assert masking_config.mask_email == True
        assert masking_config.mask_phone == True
        assert masking_config.mask_id_card == True
        assert masking_config.default_mask_char == "*"
    
    def test_custom_config_override(self, masking_builder):
        """测试：自定义配置覆盖"""
        custom_config = masking_builder.build_config(
            enabled=False,
            mask_email=False,
            mask_phone=False,
            mask_id_card=False,
            default_mask_char="X"
        )
        
        assert custom_config.enabled == False
        assert custom_config.mask_email == False
        assert custom_config.mask_phone == False
        assert custom_config.mask_id_card == False
        assert custom_config.default_mask_char == "X"
    
    def test_config_edge_values(self, masking_builder):
        """测试：配置边界值"""
        edge_configs = [
            masking_builder.build_config(enabled=False),
            masking_builder.build_config(mask_email=False),
            masking_builder.build_config(mask_phone=False),
            masking_builder.build_config(mask_id_card=False),
            masking_builder.build_config(default_mask_char="#"),
        ]
        
        for config in edge_configs:
            assert config is not None


class TestParameterValidationEdgeCases:
    """参数校验综合边界情况测试"""
    
    def test_all_role_field_combinations(self, masking_builder):
        """测试：所有角色和字段组合"""
        roles = masking_builder.build_all_roles()
        
        test_cases = [
            ("email", 1),
            ("phone", 1),
            ("id_card", 0),
            ("credit_card", 0),
            ("name", 2),
            ("address", 2),
        ]
        
        for role in roles:
            for field_name, required_level in test_cases:
                can_view = masking_builder.can_view_field(role, required_level)
                assert isinstance(can_view, bool)
    
    def test_all_rule_strategy_combinations(self, masking_builder):
        """测试：所有规则和策略组合"""
        strategies = list(MaskingStrategy)
        
        for strategy in strategies:
            rule = masking_builder.build_rule(
                field_name=f"test_{strategy.value.lower()}",
                strategy=strategy
            )
            assert rule.strategy == strategy
    
    def test_null_and_empty_parameters(self, masking_builder):
        """测试：空值和None参数"""
        edge_values = masking_builder.build_edge_case_values()
        
        for value in edge_values:
            context = masking_builder.build_context(
                user_role=UserRole.Viewer,
                additional_claims={"test_value": value}
            )
            assert context.additional_claims["test_value"] == value
    
    def test_special_characters_in_field_values(self, masking_builder):
        """测试：字段值中的特殊字符"""
        special_values = [
            "!@#$%^&*()_+-=[]{}|;':\",./<>?",
            "中文测试",
            "🎉🎊🎁",
            "\x00\x01\x02\x03",
        ]
        
        for value in special_values:
            context = masking_builder.build_context(
                user_role=UserRole.Viewer,
                additional_claims={"special": value}
            )
            assert context.additional_claims["special"] == value
    
    def test_unicode_and_emoji_in_names(self, masking_builder):
        """测试：Unicode和Emoji"""
        unicode_names = [
            "张三",
            "김철수",
            "Иван",
            "John 🎉",
        ]
        
        for name in unicode_names:
            context = masking_builder.build_context(
                user_role=UserRole.Viewer,
                additional_claims={"name": name}
            )
            assert context.additional_claims["name"] == name


class TestContextClaimsValidation:
    """上下文声明参数校验测试"""
    
    def test_empty_additional_claims(self, masking_builder):
        """测试：空的额外声明"""
        context = masking_builder.build_context(
            user_role=UserRole.Viewer,
            additional_claims={}
        )
        
        assert context.additional_claims == {}
    
    def test_large_additional_claims(self, masking_builder):
        """测试：大型额外声明"""
        large_claims = {
            "key1": "value" * 100,
            "key2": {"nested": {"deep": "value" * 50}},
            "key3": [i for i in range(100)],
        }
        
        context = masking_builder.build_context(
            user_role=UserRole.Admin,
            additional_claims=large_claims
        )
        
        assert len(str(context.additional_claims)) > 1000
    
    def test_nested_additional_claims(self, masking_builder):
        """测试：嵌套额外声明"""
        nested_claims = {
            "level1": {
                "level2": {
                    "level3": {
                        "value": "deep_nested_value"
                    }
                }
            }
        }
        
        context = masking_builder.build_context(
            user_role=UserRole.Manager,
            additional_claims=nested_claims
        )
        
        assert context.additional_claims["level1"]["level2"]["level3"]["value"] == "deep_nested_value"
