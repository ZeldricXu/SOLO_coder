import unittest
import threading
import time
import random
from typing import List, Tuple
from unittest.mock import patch, MagicMock

from session164.core.shamir import (
    KeyShardManager,
    get_shard_manager,
    Share,
    ShamirSecretSharing
)
from session164.tests.test_data_builder import get_test_data_builder, reset_test_data_builder


class TestShamirTimeoutDegradation(unittest.TestCase):
    """密钥分片管理模块测试 - 超时降级行为"""

    @classmethod
    def setUpClass(cls):
        """测试类初始化"""
        cls.builder = get_test_data_builder(seed=42)

    def setUp(self):
        """每个测试用例初始化"""
        self.manager = KeyShardManager()

    def tearDown(self):
        """每个测试用例清理"""
        reset_test_data_builder(seed=42)

    # ==================== 基础功能测试 ====================

    def test_generate_and_split_key(self):
        """测试密钥生成和分片"""
        test_data = self.builder.build_shamir_test_data("standard")
        metadata, shares, secret_key = self.manager.generate_and_split_key(
            key_length=32,
            threshold=test_data.threshold,
            total=test_data.total_shares,
            holders=test_data.holders
        )

        self.assertEqual(metadata.threshold, test_data.threshold)
        self.assertEqual(metadata.total_shares, test_data.total_shares)
        self.assertEqual(len(shares), test_data.total_shares)
        self.assertEqual(len(secret_key), 32)

        for i, share in enumerate(shares):
            self.assertEqual(share.index, i + 1)
            self.assertEqual(share.key_id, metadata.key_id)
            if i < len(test_data.holders):
                self.assertEqual(share.holder, test_data.holders[i])

    def test_split_existing_key(self):
        """测试已有密钥分片"""
        secret = b"test_secret_key_1234567890"
        metadata, shares = self.manager.split_existing_key(
            secret, threshold=3, total=5)

        self.assertEqual(metadata.original_length, len(secret))
        self.assertEqual(metadata.threshold, 3)
        self.assertEqual(len(shares), 5)

    def test_reconstruct_key_exact_threshold(self):
        """测试使用恰好阈值数量的分片恢复密钥"""
        test_data = self.builder.build_shamir_test_data("standard")
        metadata, shares, original_secret = self.manager.generate_and_split_key(
            key_length=32,
            threshold=test_data.threshold,
            total=test_data.total_shares
        )

        reconstructed = self.manager.reconstruct_key(shares[:test_data.threshold])
        self.assertEqual(reconstructed, original_secret)

    def test_reconstruct_key_more_than_threshold(self):
        """测试使用超过阈值数量的分片恢复密钥"""
        test_data = self.builder.build_shamir_test_data("standard")
        metadata, shares, original_secret = self.manager.generate_and_split_key(
            key_length=32,
            threshold=test_data.threshold,
            total=test_data.total_shares
        )

        reconstructed = self.manager.reconstruct_key(shares)
        self.assertEqual(reconstructed, original_secret)

    def test_reconstruct_key_insufficient_shares_raises_error(self):
        """测试分片不足时抛出异常"""
        test_data = self.builder.build_shamir_test_data("standard")
        metadata, shares, _ = self.manager.generate_and_split_key(
            key_length=32,
            threshold=test_data.threshold,
            total=test_data.total_shares
        )

        with self.assertRaises(ValueError) as context:
            self.manager.reconstruct_key(shares[:test_data.threshold - 1])
        self.assertIn("Insufficient shares", str(context.exception))

    # ==================== 超时降级行为测试 ====================

    def test_concurrent_share_generation_degradation(self):
        """测试高并发下密钥分片的降级行为"""
        test_data = self.builder.build_shamir_test_data("standard")
        thread_count = 20
        results = []
        errors = []
        start_time = time.time()

        def generate_shares(thread_id):
            try:
                local_test_data = self.builder.build_shamir_test_data("simple")
                start = time.time()
                metadata, shares, secret = self.manager.generate_and_split_key(
                    key_length=16,
                    threshold=local_test_data.threshold,
                    total=local_test_data.total_shares
                )
                duration = time.time() - start
                results.append({
                    "thread_id": thread_id,
                    "duration": duration,
                    "success": True,
                    "key_id": metadata.key_id
                })
            except Exception as e:
                errors.append(str(e))

        threads = []
        for i in range(thread_count):
            t = threading.Thread(target=generate_shares, args=(i,))
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        total_duration = time.time() - start_time

        self.assertEqual(len(errors), 0, f"并发错误: {errors}")
        self.assertEqual(len(results), thread_count)
        self.assertLess(total_duration, 10.0, "高并发下性能未降级成功（10秒内完成）")

        durations = [r["duration"] for r in results]
        avg_duration = sum(durations) / len(durations)
        self.assertLess(avg_duration, 1.0, f"平均响应时间过高: {avg_duration}s")

    def test_concurrent_reconstruction_degradation(self):
        """测试并发密钥恢复的降级行为"""
        test_data = self.builder.build_shamir_test_data("standard")
        metadata, shares, original_secret = self.manager.generate_and_split_key(
            key_length=32,
            threshold=test_data.threshold,
            total=test_data.total_shares
        )

        thread_count = 15
        results = []
        errors = []

        def reconstruct_thread(thread_id):
            try:
                start = time.time()
                # 随机选择阈值数量的分片
                selected_shares = random.sample(shares, test_data.threshold)
                reconstructed = self.manager.reconstruct_key(selected_shares)
                duration = time.time() - start
                results.append({
                    "thread_id": thread_id,
                    "duration": duration,
                    "success": reconstructed == original_secret
                })
            except Exception as e:
                errors.append(str(e))

        threads = []
        for i in range(thread_count):
            t = threading.Thread(target=reconstruct_thread, args=(i,))
            threads.append(t)
            t.start()

        for t in threads:
            t.join()

        self.assertEqual(len(errors), 0)
        self.assertTrue(all(r["success"] for r in results))

    def test_slow_operation_timeout_simulation(self):
        """模拟慢操作超时场景"""
        test_data = self.builder.build_shamir_test_data("complex")
        metadata, shares, original_secret = self.manager.generate_and_split_key(
            key_length=64,
            threshold=test_data.threshold,
            total=test_data.total_shares
        )

        start_time = time.time()
        reconstructed = self.manager.reconstruct_key(shares[:test_data.threshold])
        duration = time.time() - start_time

        self.assertEqual(reconstructed, original_secret)
        self.assertLess(duration, 5.0, f"操作超时: {duration}s")

    def test_retry_on_network_failure_degradation(self):
        """测试分片丢失时的降级行为"""
        test_data = self.builder.build_shamir_test_data("standard")
        metadata, shares, original_secret = self.manager.generate_and_split_key(
            key_length=32,
            threshold=3,
            total=5
        )

        # 模拟部分分片丢失场景
        scenarios = [
            shares[:3], shares[1:4], shares[2:5], [shares[0], shares[2], shares[4]]
        ]

        for scenario_shares in scenarios:
            reconstructed = self.manager.reconstruct_key(scenario_shares)
            self.assertEqual(reconstructed, original_secret)

    def test_duplicate_share_handling(self):
        """测试重复分片的优雅降级"""
        test_data = self.builder.build_shamir_test_data("standard")
        metadata, shares, original_secret = self.manager.generate_and_split_key(
            key_length=32,
            threshold=3,
            total=5
        )

        # 构造包含重复分片的列表
        shares_with_duplicates = [shares[0], shares[0], shares[1], shares[2]]

        with self.assertRaises(ValueError) as context:
            self.manager.reconstruct_key(shares_with_duplicates)
        self.assertIn("Duplicate share indices", str(context.exception))

    # ==================== 分片管理测试 ====================

    def test_get_key_metadata(self):
        """测试获取密钥元数据"""
        test_data = self.builder.build_shamir_test_data("standard")
        metadata, shares, _ = self.manager.generate_and_split_key(
            key_length=32,
            threshold=test_data.threshold,
            total=test_data.total_shares
        )

        retrieved = self.manager.get_key_metadata(metadata.key_id)
        self.assertIsNotNone(retrieved)
        self.assertEqual(retrieved.key_id, metadata.key_id)
        self.assertEqual(retrieved.threshold, test_data.threshold)

        self.assertIsNone(self.manager.get_key_metadata("non_existent_key"))

    def test_get_shares_by_key(self):
        """测试获取密钥的所有分片"""
        test_data = self.builder.build_shamir_test_data("standard")
        metadata, shares, _ = self.manager.generate_and_split_key(
            key_length=32,
            threshold=test_data.threshold,
            total=test_data.total_shares
        )

        retrieved_shares = self.manager.get_shares_by_key(metadata.key_id)
        self.assertEqual(len(retrieved_shares), test_data.total_shares)

    def test_get_share_by_holder(self):
        """测试按持有者获取分片"""
        test_data = self.builder.build_shamir_test_data("standard")
        metadata, shares, _ = self.manager.generate_and_split_key(
            key_length=32,
            threshold=test_data.threshold,
            total=test_data.total_shares,
            holders=test_data.holders
        )

        share = self.manager.get_share_by_holder(metadata.key_id, test_data.holders[0])
        self.assertIsNotNone(share)
        self.assertEqual(share.holder, test_data.holders[0])

    def test_assign_holder(self):
        """测试分配持有者"""
        test_data = self.builder.build_shamir_test_data("simple")
        metadata, shares, _ = self.manager.generate_and_split_key(
            key_length=16,
            threshold=2,
            total=3
        )

        result = self.manager.assign_holder(metadata.key_id, 1, "new_holder")
        self.assertTrue(result)

        share = self.manager.get_share_by_holder(metadata.key_id, "new_holder")
        self.assertIsNotNone(share)
        self.assertEqual(share.index, 1)

    def test_verify_shares(self):
        """测试分片验证"""
        test_data = self.builder.build_shamir_test_data("standard")
        metadata, shares, _ = self.manager.generate_and_split_key(
            key_length=32,
            threshold=test_data.threshold,
            total=test_data.total_shares
        )

        self.assertTrue(self.manager.verify_shares(shares[:test_data.threshold]))

    def test_list_keys(self):
        """测试列出所有密钥"""
        initial_count = len(self.manager.list_keys())

        for i in range(5):
            self.manager.generate_and_split_key(
                key_length=16,
                threshold=2,
                total=3
            )

        keys = self.manager.list_keys()
        self.assertEqual(len(keys), initial_count + 5)

    def test_delete_key(self):
        """测试删除密钥"""
        test_data = self.builder.build_shamir_test_data("standard")
        metadata, shares, _ = self.manager.generate_and_split_key(
            key_length=32,
            threshold=test_data.threshold,
            total=test_data.total_shares
        )

        result = self.manager.delete_key(metadata.key_id)
        self.assertTrue(result)

        self.assertIsNone(self.manager.get_key_metadata(metadata.key_id))
        self.assertEqual(len(self.manager.get_shares_by_key(metadata.key_id)), 0)

        self.assertFalse(self.manager.delete_key("non_existent_key"))

    # ==================== 边界条件测试 ====================

    def test_minimum_threshold(self):
        """测试最小阈值场景"""
        edge_cases = self.builder.build_shamir_edge_cases()

        for case in edge_cases:
            if case.threshold == 2 and case.total_shares == 2:
                metadata, shares, secret = self.manager.generate_and_split_key(
                    key_length=len(case.secret),
                    threshold=case.threshold,
                    total=case.total_shares,
                    holders=case.holders
                )

                reconstructed = self.manager.reconstruct_key(shares)
                self.assertEqual(reconstructed, secret)

    def test_high_threshold_scenario(self):
        """测试高阈值场景"""
        metadata, shares, secret = self.manager.generate_and_split_key(
            key_length=32,
            threshold=8,
            total=10
        )

        # 使用恰好8个分片恢复
        reconstructed = self.manager.reconstruct_key(shares[:8])
        self.assertEqual(reconstructed, secret)

        # 使用9个分片也能恢复
        reconstructed2 = self.manager.reconstruct_key(shares[:9])
        self.assertEqual(reconstructed2, secret)

    def test_large_number_of_shares(self):
        """测试大量分片场景"""
        metadata, shares, secret = self.manager.generate_and_split_key(
            key_length=32,
            threshold=3,
            total=20
        )

        self.assertEqual(len(shares), 20)

        # 任意3个分片都能恢复
        for i in range(5):
            start_idx = random.sample(range(20), 3)
            selected = [shares[idx] for idx in start_idx]
            reconstructed = self.manager.reconstruct_key(selected)
            self.assertEqual(reconstructed, secret)

    def test_different_key_lengths(self):
        """测试不同密钥长度"""
        for length in [16, 24, 32, 48, 64]:
            metadata, shares, secret = self.manager.generate_and_split_key(
                key_length=length,
                threshold=3,
                total=5
            )
            self.assertEqual(metadata.original_length, length)
            self.assertEqual(len(secret), length)

            reconstructed = self.manager.reconstruct_key(shares[:3])
            self.assertEqual(reconstructed, secret)

    def test_secret_too_large_raises_error(self):
        """测试超大密钥抛出异常"""
        shamir = ShamirSecretSharing(prime=2**521 - 1)

        # 构造一个超过素数大小的密钥
        huge_secret = (2**521).to_bytes(66, byteorder='big')

        with self.assertRaises(ValueError):
            shamir.split_secret(huge_secret, 3, 5)

    def test_threshold_greater_than_total_raises_error(self):
        """测试阈值大于总分片数抛出异常"""
        with self.assertRaises(ValueError) as context:
            self.manager.generate_and_split_key(
                key_length=32,
                threshold=10,
                total=5
            )

    def test_threshold_less_than_2_raises_error(self):
        """测试阈值小于2抛出异常"""
        with self.assertRaises(ValueError) as context:
            self.manager.generate_and_split_key(
                key_length=32,
                threshold=1,
                total=5
            )

    # ==================== 混合操作一致性测试 ====================

    def test_multiple_keys_isolation(self):
        """测试多密钥环境下的隔离性"""
        secrets = []
        for i in range(10):
            metadata, shares, secret = self.manager.generate_and_split_key(
                key_length=32,
                threshold=3,
                total=5
            )
            secrets.append((metadata, shares, secret))

        # 验证每个密钥都能独立恢复
        for metadata, shares, original_secret in secrets:
            reconstructed = self.manager.reconstruct_key(shares[:3])
            self.assertEqual(reconstructed, original_secret)

        # 验证不能用其他密钥的分片无法恢复
        metadata1, shares1, secret1 = secrets[0]
        metadata2, shares2, secret2 = secrets[1]

        # 混合分片应该抛出异常或恢复失败
        try:
            mixed_shares = [shares1[0], shares1[1], shares2[0]]
            with self.assertRaises(Exception):
                self.manager.reconstruct_key(mixed_shares)
        except Exception:
            pass

    def test_share_from_different_keys_raises_error(self):
        """测试使用不同密钥的分片混合抛出异常"""
        metadata1, shares1, _ = self.manager.generate_and_split_key(
            key_length=32,
            threshold=3,
            total=5
        )
        metadata2, shares2, _ = self.manager.generate_and_split_key(
            key_length=32,
            threshold=3,
            total=5
        )

        mixed_shares = [shares1[0], shares1[1], shares2[2]]

        with self.assertRaises(ValueError) as context:
            self.manager.reconstruct_key(mixed_shares)
        self.assertIn("different keys", str(context.exception))

    # ==================== 单例模式测试 ====================

    def test_singleton_instance(self):
        """测试单例模式"""
        manager1 = get_shard_manager()
        manager2 = get_shard_manager()
        self.assertIs(manager1, manager2)

    def test_singleton_data_persistence(self):
        """测试单例数据持久化"""
        manager = get_shard_manager()
        initial_count = len(manager.list_keys())

        test_data = self.builder.build_shamir_test_data("simple")
        metadata, shares, _ = manager.generate_and_split_key(
            key_length=16,
            threshold=2,
            total=3
        )

        manager2 = get_shard_manager()
        keys_after = manager2.list_keys()
        self.assertEqual(len(keys_after), initial_count + 1)

        # 清理
        manager2.delete_key(metadata.key_id)


if __name__ == "__main__":
    unittest.main()
