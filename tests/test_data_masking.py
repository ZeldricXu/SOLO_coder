import unittest
import threading
import time
import copy
from typing import Dict, Any
from unittest.mock import patch, MagicMock

from session164.core.data_masking import (
    DataMaskingEngine,
    get_masking_engine,
    MaskingRule,
    UserRole
)
from session164.tests.test_data_builder import get_test_data_builder, reset_test_data_builder


class TestDataMaskingConcurrencyIsolation(unittest.TestCase):
    """动态数据脱敏模块测试 - 并发隔离级别"""

    @classmethod
    def setUpClass(cls):
        """测试类初始化"""
        cls.builder = get_test_data_builder(seed=42)

    def setUp(self):
        """每个测试用例初始化"""
        self.engine = DataMaskingEngine()

    def tearDown(self):
        """每个测试用例清理"""
        reset_test_data_builder(seed=42)

    # ==================== 基础功能测试 ====================

    def test_default_roles_exist(self):
        """测试默认角色存在"""
        roles = self.engine.get_available_roles()
        self.assertIn("admin", roles)
        self.assertIn("user", roles)
        self.assertIn("guest", roles)

    def test_admin_role_no_masking(self):
        """测试admin角色不进行脱敏"""
        test_data = self.builder.build_masking_test_data("low")
        result = self.engine.mask_data(test_data.original_data, "admin")

        for field in test_data.original_data:
            self.assertEqual(result[field], test_data.original_data[field])

    def test_user_role_partial_masking(self):
        """测试user角色部分脱敏"""
        test_data = self.builder.build_masking_test_data("medium")
        result = self.engine.mask_data(test_data.original_data, "user")

        for field in test_data.expected_masked_fields:
            self.assertNotEqual(result[field], test_data.original_data[field])

        for field in test_data.expected_visible_fields:
            self.assertEqual(result[field], test_data.original_data[field])

    def test_guest_role_full_masking(self):
        """测试guest角色完整脱敏"""
        test_data = self.builder.build_masking_test_data("high")
        result = self.engine.mask_data(test_data.original_data, "guest")

        for field in test_data.expected_masked_fields:
            self.assertNotEqual(result[field], test_data.original_data[field])

    def test_unknown_role_defaults_to_guest(self):
        """测试未知角色默认使用guest策略"""
        test_data = self.builder.build_masking_test_data("high")
        result = self.engine.mask_data(test_data.original_data, "unknown_role")
        guest_result = self.engine.mask_data(test_data.original_data, "guest")

        self.assertEqual(result, guest_result)

    # ==================== 脱敏规则类型测试 ====================

    def test_mask_rule_with_visible_parts(self):
        """测试掩码规则 - 显示前后部分"""
        test_data = {"phone": "13800138000"}
        result = self.engine.mask_data(test_data, "user")

        self.assertTrue(result["phone"].startswith("138"))
        self.assertTrue(result["phone"].endswith("8000"))
        self.assertIn("****", result["phone"])

    def test_replace_rule(self):
        """测试替换规则"""
        test_data = {"password": "my_secret_password_123"}
        result = self.engine.mask_data(test_data, "user")

        self.assertEqual(result["password"], "***")

    def test_hash_rule(self):
        """测试哈希规则"""
        engine = DataMaskingEngine()
        rule = MaskingRule(
            field_name="email",
            rule_type="hash",
            hash_algorithm="sha256"
        )
        engine.add_masking_rule("guest", rule)

        test_data = {"email": "test@example.com"}
        result = engine.mask_data(test_data, "guest")

        self.assertNotEqual(result["email"], "test@example.com")
        self.assertEqual(len(result["email"]), 64)

    def test_truncate_rule(self):
        """测试截断规则"""
        engine = DataMaskingEngine()
        rule = MaskingRule(
            field_name="description",
            rule_type="truncate",
            visible_start=10
        )
        engine.add_masking_rule("guest", rule)

        test_data = {"description": "This is a very long description that should be truncated"}
        result = engine.mask_data(test_data, "guest")

        self.assertTrue(len(result["description"]) <= 13)
        self.assertTrue(result["description"].endswith("..."))

    # ==================== 嵌套数据结构测试 ====================

    def test_nested_data_masking(self):
        """测试嵌套数据脱敏"""
        test_data = self.builder.build_masking_nested_data()
        result = self.engine.mask_data(test_data.original_data, "user")

        self.assertEqual(result["user"]["basic"]["name"], "张三")
        self.assertNotEqual(result["user"]["basic"]["email"],
                           test_data.original_data["user"]["basic"]["email"])
        self.assertNotEqual(result["user"]["identity"]["id_card"],
                           test_data.original_data["user"]["identity"]["id_card"])

    def test_list_data_masking(self):
        """测试列表数据脱敏"""
        test_data = self.builder.build_masking_list_data(5)
        result = self.engine.mask_data(test_data.original_data, "user")

        self.assertEqual(result["total"], test_data.original_data["total"])
        for i, user in enumerate(result["users"]):
            self.assertEqual(user["id"], test_data.original_data["users"][i]["id"])
            self.assertEqual(user["name"], test_data.original_data["users"][i]["name"])
            self.assertNotEqual(user["email"], test_data.original_data["users"][i]["email"])

    def test_deeply_nested_structure(self):
        """测试深度嵌套结构"""
        deep_data = {
            "level1": {
                "level2": {
                    "level3": {
                        "level4": {
                            "email": "deep@example.com",
                            "phone": "13800138000",
                            "public_field": "visible"
                        }
                    }
                }
            }
        }

        result = self.engine.mask_data(deep_data, "user")
        self.assertNotEqual(result["level1"]["level2"]["level3"]["level4"]["email"],
                           "deep@example.com")
        self.assertNotEqual(result["level1"]["level2"]["level3"]["level4"]["phone"],
                           "13800138000")
        self.assertEqual(result["level1"]["level2"]["level3"]["level4"]["public_field"],
                        "visible")

    # ==================== 并发隔离级别测试 ====================

    def test_concurrent_masking_same_role(self):
        """测试同一角色的并发脱敏 - 验证结果一致性"""
        test_data = self.builder.build_masking_test_data("medium")
        thread_count = 10
        results = []
        errors = []

        def mask_thread():
            try:
                for _ in range(20):
                    result = self.engine.mask_data(
                        copy.deepcopy(test_data.original_data),
                        "user"
                    )
                    results.append(result)
            except Exception as e:
                errors.append(str(e))

        threads = []
        for _ in range(thread_count):
            t = threading.Thread(target=mask_thread)
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        self.assertEqual(len(errors), 0, f"并发错误: {errors}")
        self.assertEqual(len(results), thread_count * 20)

        expected_result = self.engine.mask_data(test_data.original_data, "user")
        for result in results:
            self.assertEqual(result, expected_result)

    def test_concurrent_masking_different_roles(self):
        """测试不同角色的并发脱敏 - 验证隔离性"""
        test_data = self.builder.build_masking_test_data("medium")
        roles = ["admin", "user", "guest"]
        results_by_role = {role: [] for role in roles}
        errors = []

        def mask_for_role(role):
            try:
                for _ in range(15):
                    result = self.engine.mask_data(
                        copy.deepcopy(test_data.original_data),
                        role
                    )
                    results_by_role[role].append(result)
                    time.sleep(0.001)
            except Exception as e:
                errors.append(str(e))

        threads = []
        for role in roles:
            t = threading.Thread(target=mask_for_role, args=(role,))
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        self.assertEqual(len(errors), 0)

        # 验证每个角色的结果正确且一致
        for role in roles:
            expected = self.engine.mask_data(test_data.original_data, role)
            for result in results_by_role[role]:
                self.assertEqual(result, expected)

        # 验证不同角色的结果不同
        admin_result = results_by_role["admin"][0]
        user_result = results_by_role["user"][0]
        guest_result = results_by_role["guest"][0]

        self.assertNotEqual(admin_result, user_result)
        self.assertNotEqual(user_result, guest_result)

    def test_concurrent_role_modification_and_masking(self):
        """测试并发修改角色配置和脱敏 - 验证线程安全"""
        test_data = self.builder.build_masking_test_data("medium")
        results = []
        errors = []

        def modify_roles():
            try:
                for i in range(10):
                    custom_rule = MaskingRule(
                        field_name=f"custom_field_{i}",
                        rule_type="replace",
                        replacement="***"
                    )
                    new_role = UserRole(
                        role_name=f"custom_role_{i}",
                        allowed_fields=set(),
                        masked_fields={f"custom_field_{i}": custom_rule}
                    )
                    self.engine.add_role(new_role)
                    time.sleep(0.002)
            except Exception as e:
                errors.append(str(e))

        def perform_masking():
            try:
                for _ in range(30):
                    result = self.engine.mask_data(
                        copy.deepcopy(test_data.original_data),
                        "user"
                    )
                    results.append(result)
                    time.sleep(0.001)
            except Exception as e:
                errors.append(str(e))

        threads = []
        threads.append(threading.Thread(target=modify_roles))
        for _ in range(3):
            t = threading.Thread(target=perform_masking)
            threads.append(t)

        for t in threads:
            t.start()
        for t in threads:
            t.join()

        self.assertEqual(len(errors), 0, f"并发修改错误: {errors}")
        self.assertGreater(len(results), 0)

    def test_concurrent_rule_modification_isolation(self):
        """测试并发规则修改的隔离性"""
        errors = []
        rule_add_count = 0
        rule_remove_count = 0

        def add_rules():
            nonlocal rule_add_count
            try:
                for i in range(20):
                    rule = MaskingRule(
                        field_name=f"field_{i}",
                        rule_type="replace",
                        replacement="***"
                    )
                    self.engine.add_masking_rule("guest", rule)
                    rule_add_count += 1
                    time.sleep(0.001)
            except Exception as e:
                errors.append(str(e))

        def remove_rules():
            nonlocal rule_remove_count
            try:
                for i in range(10):
                    self.engine.remove_masking_rule("guest", f"field_{i * 2}")
                    rule_remove_count += 1
                    time.sleep(0.002)
            except Exception as e:
                errors.append(str(e))

        threads = [
            threading.Thread(target=add_rules),
            threading.Thread(target=remove_rules)
        ]

        for t in threads:
            t.start()
        for t in threads:
            t.join()

        self.assertEqual(len(errors), 0)
        self.assertEqual(rule_add_count, 20)

    # ==================== 角色和规则管理测试 ====================

    def test_add_and_remove_role(self):
        """测试添加和删除角色"""
        new_role = UserRole(
            role_name="custom_role",
            allowed_fields=set(),
            masked_fields={}
        )

        self.engine.add_role(new_role)
        self.assertIn("custom_role", self.engine.get_available_roles())

        self.engine.remove_role("custom_role")
        self.assertNotIn("custom_role", self.engine.get_available_roles())

    def test_add_and_remove_masking_rule(self):
        """测试添加和删除脱敏规则"""
        rule = MaskingRule(
            field_name="custom_field",
            rule_type="replace",
            replacement="***"
        )

        result = self.engine.add_masking_rule("user", rule)
        self.assertTrue(result)

        rules = self.engine.get_role_rules("user")
        self.assertIn("custom_field", rules["masked_fields"])

        result = self.engine.remove_masking_rule("user", "custom_field")
        self.assertTrue(result)

    def test_get_role_rules(self):
        """测试获取角色规则"""
        rules = self.engine.get_role_rules("user")
        self.assertIsNotNone(rules)
        self.assertEqual(rules["role_name"], "user")
        self.assertIsInstance(rules["masked_fields"], dict)

        self.assertIsNone(self.engine.get_role_rules("non_existent_role"))

    def test_remove_nonexistent_role_returns_false(self):
        """测试删除不存在的角色返回False"""
        result = self.engine.remove_role("non_existent")
        self.assertFalse(result)

    def test_remove_nonexistent_rule_returns_false(self):
        """测试删除不存在的规则返回False"""
        result = self.engine.remove_masking_rule("user", "non_existent_field")
        self.assertFalse(result)

    # ==================== 自动检测脱敏测试 ====================

    def test_auto_detect_masking(self):
        """测试自动检测脱敏"""
        test_data = {
            "contact": "13800138000",
            "user_email": "user@example.com",
            "identity": "110101199001011234",
            "normal_field": "normal_value"
        }

        result = self.engine.auto_detect_and_mask(test_data, "user")

        self.assertIn("****", result["contact"])
        self.assertIn("***@", result["user_email"])
        self.assertIn("********", result["identity"])
        self.assertEqual(result["normal_field"], "normal_value")

    def test_auto_detect_mixed_data(self):
        """测试混合数据的自动检测脱敏"""
        mixed_data = [
            {"name": "张三", "phone": "13800138000", "age": 25},
            {"name": "李四", "phone": "13900139000", "age": 30},
            {"name": "王五", "phone": "13700137000", "age": 35}
        ]

        result = self.engine.auto_detect_and_mask(mixed_data, "user")

        for item in result:
            self.assertIn("****", item["phone"])
            self.assertEqual(item["name"], item["name"])
            self.assertEqual(item["age"], item["age"])

    # ==================== 边界条件测试 ====================

    def test_empty_data_masking(self):
        """测试空数据脱敏"""
        result = self.engine.mask_data({}, "user")
        self.assertEqual(result, {})

    def test_none_value_masking(self):
        """测试None值脱敏"""
        test_data = {"field1": None, "field2": "value", "phone": None}
        result = self.engine.mask_data(test_data, "user")

        self.assertIsNone(result["field1"])
        self.assertEqual(result["field2"], "value")
        self.assertIsNone(result["phone"])

    def test_large_data_masking(self):
        """测试大数据量脱敏"""
        large_data = {}
        for i in range(100):
            large_data[f"field_{i}"] = f"value_{i}"
            if i % 10 == 0:
                large_data[f"email_{i}"] = f"user{i}@example.com"
                large_data[f"phone_{i}"] = f"13800{i:06d}"

        start_time = time.time()
        result = self.engine.mask_data(large_data, "user")
        duration = time.time() - start_time

        self.assertLess(duration, 1.0)
        self.assertEqual(len(result), len(large_data))

    def test_special_characters_masking(self):
        """测试特殊字符脱敏"""
        special_data = {
            "email": "user.name+tag@example.com",
            "phone": "+8613800138000",
            "notes": 'Contains "quotes" and \'apostrophes\''
        }

        result = self.engine.mask_data(special_data, "user")
        self.assertNotEqual(result["email"], special_data["email"])
        self.assertNotEqual(result["phone"], special_data["phone"])
        self.assertEqual(result["notes"], special_data["notes"])

    # ==================== 数据不变性测试 ====================

    def test_original_data_not_modified(self):
        """测试原始数据不被修改"""
        test_data = self.builder.build_masking_test_data("high")
        original_copy = copy.deepcopy(test_data.original_data)

        self.engine.mask_data(test_data.original_data, "user")

        self.assertEqual(test_data.original_data, original_copy)

    def test_masking_is_deterministic(self):
        """测试脱敏结果的确定性"""
        test_data = self.builder.build_masking_test_data("medium")

        result1 = self.engine.mask_data(test_data.original_data, "user")
        result2 = self.engine.mask_data(test_data.original_data, "user")
        result3 = self.engine.mask_data(copy.deepcopy(test_data.original_data), "user")

        self.assertEqual(result1, result2)
        self.assertEqual(result2, result3)

    # ==================== 单例模式测试 ====================

    def test_singleton_instance(self):
        """测试单例模式"""
        engine1 = get_masking_engine()
        engine2 = get_masking_engine()
        self.assertIs(engine1, engine2)

    def test_singleton_role_persistence(self):
        """测试单例角色配置持久化"""
        engine = get_masking_engine()
        initial_roles = engine.get_available_roles()

        new_role = UserRole(
            role_name="test_singleton_role",
            allowed_fields=set(),
            masked_fields={}
        )
        engine.add_role(new_role)

        engine2 = get_masking_engine()
        self.assertIn("test_singleton_role", engine2.get_available_roles())

        # 清理
        engine2.remove_role("test_singleton_role")


if __name__ == "__main__":
    unittest.main()
