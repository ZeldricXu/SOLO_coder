import unittest
import threading
import time
from typing import List
from unittest.mock import patch, MagicMock

from session164.core.audit_chain import AuditChain, get_audit_chain, AuditLogEntry
from session164.tests.test_data_builder import get_test_data_builder, reset_test_data_builder


class TestAuditChainDataConsistency(unittest.TestCase):
    """审计日志防篡改模块测试 - 数据一致性保障"""

    @classmethod
    def setUpClass(cls):
        """测试类初始化"""
        cls.builder = get_test_data_builder(seed=42)

    def setUp(self):
        """每个测试用例初始化 - 每个测试都使用独立的链实例"""
        self.chain = AuditChain()

    def tearDown(self):
        """每个测试用例清理"""
        reset_test_data_builder(seed=42)

    # ==================== 基础功能测试 ====================

    def test_genesis_block_creation(self):
        """测试创世块创建"""
        self.assertEqual(self.chain.chain[0].sequence, 0)
        self.assertEqual(self.chain.chain[0].action, "genesis")
        self.assertEqual(self.chain.chain[0].previous_hash, "0" * 64)
        self.assertIsNotNone(self.chain.chain[0].current_hash)

    def test_single_entry_addition(self):
        """测试单条日志添加"""
        test_data = self.builder.build_audit_log_entry("login")
        entry = self.chain.add_entry(
            test_data.action, test_data.actor, test_data.resource, test_data.details
        )

        self.assertEqual(entry.sequence, 1)
        self.assertEqual(entry.action, test_data.action)
        self.assertEqual(entry.actor, test_data.actor)
        self.assertEqual(entry.resource, test_data.resource)
        self.assertIsNotNone(entry.current_hash)
        self.assertEqual(entry.previous_hash, self.chain.chain[0].current_hash)

    def test_multiple_entries_sequential_order(self):
        """测试多条日志的顺序性"""
        test_entries = self.builder.build_audit_log_batch(10)
        added_entries = []

        for test_data in test_entries:
            entry = self.chain.add_entry(
                test_data.action, test_data.actor, test_data.resource, test_data.details
            )
            added_entries.append(entry)

        self.assertEqual(len(self.chain.chain), 11)  # 创世块 + 10条日志

        for i, entry in enumerate(added_entries):
            self.assertEqual(entry.sequence, i + 1)
            if i > 0:
                self.assertEqual(entry.previous_hash, added_entries[i - 1].current_hash)

    # ==================== 数据一致性测试 ====================

    def test_chain_integrity_verification_clean_chain(self):
        """测试完整链的完整性验证"""
        test_entries = self.builder.build_audit_log_batch(20)

        for test_data in test_entries:
            self.chain.add_entry(
                test_data.action, test_data.actor, test_data.resource, test_data.details
            )

        result = self.chain.verify_integrity()
        self.assertTrue(result["is_valid"])
        self.assertEqual(result["total_blocks"], 21)
        self.assertEqual(len(result["errors"]), 0)

    def test_chain_integrity_after_tampering_detected(self):
        """测试篡改后完整性验证失败"""
        test_entries = self.builder.build_audit_log_batch(10)

        for test_data in test_entries:
            self.chain.add_entry(
                test_data.action, test_data.actor, test_data.resource, test_data.details
            )

        # 篡改第5条日志
        self.chain.chain[5].details["tampered"] = True

        result = self.chain.verify_integrity()
        self.assertFalse(result["is_valid"])
        self.assertGreater(len(result["errors"]), 0)

    def test_tamper_detection_specific_range(self):
        """测试指定范围内的篡改检测"""
        test_entries = self.builder.build_audit_log_batch(15)

        for test_data in test_entries:
            self.chain.add_entry(
                test_data.action, test_data.actor, test_data.resource, test_data.details
            )

        # 篡改第8条日志
        original_action = self.chain.chain[8].action
        self.chain.chain[8].action = "tampered_action"

        result = self.chain.detect_tampering(5, 12)
        self.assertGreater(result["tampered_count"], 0)
        self.assertIn(8, result["tampered_sequences"])

        # 验证未篡改区域
        result_clean = self.chain.detect_tampering(1, 7)
        self.assertEqual(result_clean["tampered_count"], 0)

    def test_hash_chain_linkage(self):
        """测试哈希链的链接正确性"""
        test_entries = self.builder.build_audit_log_batch(5)
        entries = []

        for test_data in test_entries:
            entry = self.chain.add_entry(
                test_data.action, test_data.actor, test_data.resource, test_data.details
            )
            entries.append(entry)

        # 验证每个块的previous_hash等于前一个块的current_hash
        for i in range(1, len(entries)):
            self.assertEqual(
                entries[i].previous_hash,
                entries[i - 1].current_hash
            )

        # 验证第一个块链接到创世块
        self.assertEqual(entries[0].previous_hash, self.chain.chain[0].current_hash)

    def test_hash_cannot_modify_old_entry(self):
        """测试修改旧条目会导致后续所有哈希失效"""
        test_entries = self.builder.build_audit_log_batch(10)

        for test_data in test_entries:
            self.chain.add_entry(
                test_data.action, test_data.actor, test_data.resource, test_data.details
            )

        # 保存原始哈希
        original_hashes = [entry.current_hash for entry in self.chain.chain]

        # 修改第3条日志
        self.chain.chain[3].details["modified"] = True

        # 重新计算从第3条开始的哈希应该变化
        from session164.core.utils import hash_data
        recalculated = hash_data({
            "action": self.chain.chain[3].action,
            "actor": self.chain.chain[3].actor,
            "resource": self.chain.chain[3].resource,
            "details": self.chain.chain[3].details,
            "sequence": self.chain.chain[3].sequence,
            "previous_hash": self.chain.chain[3].previous_hash
        })

        # 第3条及之后的哈希应该都变化了
        self.assertNotEqual(recalculated, original_hashes[3])

    # ==================== 并发一致性测试 ====================

    def test_concurrent_log_addition(self):
        """测试并发添加日志的一致性"""
        thread_count = 5
        entries_per_thread = 20
        errors = []

        def add_logs(thread_id):
            try:
                local_entries = self.builder.build_audit_log_batch(entries_per_thread)
                for test_data in local_entries:
                    self.chain.add_entry(
                        f"thread_{thread_id}_{test_data.action}",
                        test_data.actor,
                        test_data.resource,
                        test_data.details
                    )
            except Exception as e:
                errors.append(str(e))

        threads = []
        for i in range(thread_count):
            t = threading.Thread(target=add_logs, args=(i,))
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        # 验证没有错误
        self.assertEqual(len(errors), 0, f"并发错误: {errors}")

        # 验证总条目数正确
        expected_total = 1 + thread_count * entries_per_thread  # 创世块 + 所有日志
        self.assertEqual(len(self.chain.chain), expected_total)

        # 验证序列号连续且唯一
        sequences = [entry.sequence for entry in self.chain.chain]
        self.assertEqual(len(sequences), len(set(sequences)))
        self.assertEqual(min(sequences), 0)
        self.assertEqual(max(sequences), expected_total - 1)

        # 验证链完整性
        integrity_result = self.chain.verify_integrity()
        self.assertTrue(integrity_result["is_valid"],
                         f"并发后链完整性验证失败: {integrity_result['errors']}")

    def test_concurrent_read_write(self):
        """测试并发读写的一致性"""
        # 先添加一些基础日志
        for test_data in self.builder.build_audit_log_batch(10):
            self.chain.add_entry(
                test_data.action, test_data.actor, test_data.resource, test_data.details
            )

        read_results = []
        write_errors = []

        def writer():
            try:
                for _ in range(10):
                    test_data = self.builder.build_audit_log_entry("query")
                    self.chain.add_entry(
                        test_data.action, test_data.actor, test_data.resource, test_data.details
                    )
                    time.sleep(0.001)
            except Exception as e:
                write_errors.append(str(e))

        def reader():
            try:
                for _ in range(10):
                    result = self.chain.verify_integrity()
                    read_results.append(result["is_valid"])
                    time.sleep(0.001)
            except Exception as e:
                        read_results.append(False)

        threads = []
        for _ in range(3):
            t = threading.Thread(target=writer)
            threads.append(t)
            t.start()
        for _ in range(2):
            t = threading.Thread(target=reader)
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        self.assertEqual(len(write_errors), 0)
        # 所有读取操作都应该返回True（链始终保持一致）
        self.assertTrue(all(read_results), f"并发读取时发现不一致")

    # ==================== 查询功能测试 ====================

    def test_get_entry_by_sequence(self):
        """测试按序列号查询"""
        test_entries = self.builder.build_audit_log_batch(10)

        for test_data in test_entries:
            self.chain.add_entry(
                test_data.action, test_data.actor, test_data.resource, test_data.details
            )

        # 查询存在的条目
        entry = self.chain.get_entry_by_sequence(5)
        self.assertIsNotNone(entry)
        self.assertEqual(entry.sequence, 5)

        # 查询不存在的条目
        self.assertIsNone(self.chain.get_entry_by_sequence(100))
        self.assertIsNone(self.chain.get_entry_by_sequence(-1))

    def test_get_entries_by_action(self):
        """测试按操作类型查询"""
        action_types = ["user_login", "user_logout", "resource_create", "resource_update", "resource_delete"]
        for action in action_types:
            for _ in range(3):
                self.chain.add_entry(
                    action, f"user_{action}", f"resource_{action}", {"test": True}
                )

        for action in action_types:
            entries = self.chain.get_entries_by_action(action)
            self.assertEqual(len(entries), 3)
            for entry in entries:
                self.assertEqual(entry.action, action)

    def test_chain_to_dict(self):
        """测试链导出为字典"""
        test_entries = self.builder.build_audit_log_batch(5)
        for test_data in test_entries:
            self.chain.add_entry(
                test_data.action, test_data.actor, test_data.resource, test_data.details
            )

        chain_dict = self.chain.to_dict()
        self.assertEqual(len(chain_dict), 6)

        # 验证导出数据的完整性
        for i, entry_dict in enumerate(chain_dict):
            self.assertIn("log_id", entry_dict)
            self.assertIn("sequence", entry_dict)
            self.assertIn("action", entry_dict)
            self.assertIn("current_hash", entry_dict)
            self.assertIn("previous_hash", entry_dict)

    # ==================== 边界条件测试 ====================

    def test_empty_details(self):
        """测试空详情字段"""
        entry = self.chain.add_entry("test_action", "test_actor", "test_resource", {})
        self.assertEqual(entry.details, {})

        # 验证完整性
        result = self.chain.verify_integrity()
        self.assertTrue(result["is_valid"])

    def test_large_details(self):
        """测试大体积详情字段"""
        large_details = {
            "field_" + str(i): "value_" * 100 for i in range(100)
        }
        entry = self.chain.add_entry("large_data", "system", "data_import", large_details)

        self.assertIsNotNone(entry.current_hash)
        self.assertEqual(entry.details, large_details)

        # 验证完整性
        result = self.chain.verify_integrity()
        self.assertTrue(result["is_valid"])

    def test_special_characters_in_details(self):
        """测试详情中的特殊字符"""
        special_details = {
            "unicode": "测试中文特殊字符",
            "quotes": 'He said "Hello"',
            "newlines": "line1\nline2",
            "special": "!@#$%^&*()"
        }
        entry = self.chain.add_entry("special_chars", "tester", "test_resource", special_details)

        self.assertEqual(entry.details, special_details)
        result = self.chain.verify_integrity()
        self.assertTrue(result["is_valid"])

    def test_very_long_chain(self):
        """测试非常长的链"""
        test_entries = self.builder.build_audit_log_batch(100)

        for test_data in test_entries:
            self.chain.add_entry(
                test_data.action, test_data.actor, test_data.resource, test_data.details
            )

        self.assertEqual(len(self.chain.chain), 101)

        # 验证完整性
        result = self.chain.verify_integrity()
        self.assertTrue(result["is_valid"])

        # 验证随机位置的条目
        for seq in [1, 25, 50, 75, 100]:
            entry = self.chain.get_entry_by_sequence(seq)
            self.assertIsNotNone(entry)
            self.assertEqual(entry.sequence, seq)

    # ==================== 篡改检测场景测试 ====================

    def test_tamper_multiple_blocks(self):
        """测试多个块被篡改的检测"""
        test_entries = self.builder.build_audit_log_batch(20)
        for test_data in test_entries:
            self.chain.add_entry(
                test_data.action, test_data.actor, test_data.resource, test_data.details
            )

        # 篡改多个块
        tampered_sequences = [3, 7, 12, 15]
        for seq in tampered_sequences:
            self.chain.chain[seq].details["hacked"] = True

        result = self.chain.detect_tampering()
        self.assertGreaterEqual(result["tampered_count"], len(tampered_sequences))

    def test_tamper_genesis_block(self):
        """测试创世块被篡改"""
        test_entries = self.builder.build_audit_log_batch(5)
        for test_data in test_entries:
            self.chain.add_entry(
                test_data.action, test_data.actor, test_data.resource, test_data.details
            )

        # 篡改创世块
        self.chain.chain[0].action = "hacked_genesis"

        result = self.chain.verify_integrity()
        self.assertFalse(result["is_valid"])

    def test_partial_chain_verification(self):
        """测试部分链验证"""
        test_entries = self.builder.build_audit_log_batch(15)
        for test_data in test_entries:
            self.chain.add_entry(
                test_data.action, test_data.actor, test_data.resource, test_data.details
            )

        # 篡改第10条以后的
        self.chain.chain[10].actor = "attacker"

        # 只验证前10条
        result_clean = self.chain.detect_tampering(0, 10)
        self.assertEqual(result_clean["tampered_count"], 0)

        # 验证全部
        result_all = self.chain.detect_tampering()
        self.assertGreater(result_all["tampered_count"], 0)

    # ==================== 单例模式测试 ====================

    def test_singleton_instance(self):
        """测试单例模式"""
        chain1 = get_audit_chain()
        chain2 = get_audit_chain()
        self.assertIs(chain1, chain2)

    def test_singleton_persistence(self):
        """测试单例数据持久化"""
        chain = get_audit_chain()
        initial_length = len(chain.chain)

        test_data = self.builder.build_audit_log_entry("test")
        chain.add_entry(test_data.action, test_data.actor, test_data.resource, test_data.details)

        chain2 = get_audit_chain()
        self.assertEqual(len(chain2.chain), initial_length + 1)


if __name__ == "__main__":
    unittest.main()
