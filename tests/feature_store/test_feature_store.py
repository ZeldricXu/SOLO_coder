"""
特征存储服务模块单元测试
测试场景：
1. 数据一致性保障
2. 并发隔离级别
3. 超时降级行为
"""
import pytest
import asyncio
from typing import Dict, Any
import time

from tests.base_test import BaseTest, MockBaseTest
from tests.data_factory import get_factory


pytestmark = pytest.mark.feature_store


class TestFeatureStoreConsistency(BaseTest):
    """数据一致性保障测试"""

    @pytest.mark.consistency
    @pytest.mark.smoke
    async def test_feature_register_and_query_consistency(self):
        """测试特征注册与查询的数据一致性"""
        # 1. 准备测试数据
        feature_data = self.factory.create_feature_data()

        # 2. 注册特征
        register_resp = await self.client.post(
            "/feature-store/features",
            json=feature_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)

        feature_id = register_resp.result.get('feature_id')
        assert feature_id is not None, "Feature ID should not be None"
        self.register_resource("/feature-store/features", feature_id)

        # 3. 查询特征
        query_resp = await self.client.get(f"/feature-store/features/{feature_id}")
        self.assert_success(query_resp)

        # 4. 验证数据一致性
        stored_feature = query_resp.result
        self.assert_data_consistency(
            feature_data.to_dict(),
            stored_feature,
            keys=['feature_name', 'feature_type', 'entity', 'value_type', 'owner']
        )

    @pytest.mark.consistency
    async def test_feature_value_write_read_consistency(self):
        """测试特征值写入与读取的数据一致性"""
        # 1. 先注册特征
        feature_data = self.factory.create_feature_data()
        register_resp = await self.client.post(
            "/feature-store/features",
            json=feature_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        feature_id = register_resp.result.get('feature_id')
        self.register_resource("/feature-store/features", feature_id)

        # 2. 写入特征值
        value_data = self.factory.create_feature_value_data(feature_id)
        ingest_resp = await self.client.post(
            "/feature-store/values/ingest",
            json=value_data.to_dict()
        )
        self.assert_success(ingest_resp)

        # 3. 读取最新特征值
        read_resp = await self.client.get(
            "/feature-store/values/latest",
            params={"feature_id": feature_id, "entity_key": value_data.entity_key}
        )
        self.assert_success(read_resp)

        # 4. 验证一致性
        stored_value = read_resp.result
        assert stored_value is not None
        assert stored_value.get('feature_id') == feature_id
        assert stored_value.get('entity_key') == value_data.entity_key
        assert stored_value.get('value') == value_data.value

    @pytest.mark.consistency
    async def test_batch_ingest_consistency(self):
        """测试批量写入的一致性"""
        # 1. 注册特征
        feature_data = self.factory.create_feature_data()
        register_resp = await self.client.post(
            "/feature-store/features",
            json=feature_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        feature_id = register_resp.result.get('feature_id')
        self.register_resource("/feature-store/features", feature_id)

        # 2. 准备批量数据
        batch_size = 10
        batch_data = []
        entity_keys = []
        for i in range(batch_size):
            value_data = self.factory.create_feature_value_data(feature_id)
            batch_data.append(value_data.to_dict())
            entity_keys.append(value_data.entity_key)

        # 3. 批量写入
        batch_resp = await self.client.post(
            "/feature-store/values/batch-ingest",
            json=batch_data
        )
        self.assert_success(batch_resp)

        # 4. 逐一验证每条数据
        for entity_key in entity_keys:
            read_resp = await self.client.get(
                "/feature-store/values/latest",
                params={"feature_id": feature_id, "entity_key": entity_key}
            )
            self.assert_success(read_resp)
            assert read_resp.result is not None
            assert read_resp.result.get('entity_key') == entity_key

    @pytest.mark.consistency
    async def test_time_range_query_consistency(self):
        """测试时间范围查询的一致性"""
        # 1. 注册特征
        feature_data = self.factory.create_feature_data()
        register_resp = await self.client.post(
            "/feature-store/features",
            json=feature_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        feature_id = register_resp.result.get('feature_id')
        self.register_resource("/feature-store/features", feature_id)

        # 2. 写入多个时间点的特征值
        entity_key = f"test_user_{int(time.time())}"
        timestamps = []
        for i in range(5):
            timestamp_ms = int(time.time() * 1000) - (4 - i) * 60000
            value_data = self.factory.create_feature_value_data(feature_id, entity_key)
            ingest_resp = await self.client.post(
                "/feature-store/values/ingest",
                json={
                    **value_data.to_dict(),
                    "timestamp_ms": timestamp_ms
                }
            )
            self.assert_success(ingest_resp)
            timestamps.append(timestamp_ms)

        # 3. 查询时间范围内的数据
        start_time = time.strftime('%Y-%m-%dT%H:%M:%S', time.localtime(timestamps[0] / 1000))
        end_time = time.strftime('%Y-%m-%dT%H:%M:%S', time.localtime(timestamps[-1] / 1000 + 60))

        range_resp = await self.client.get(
            "/feature-store/values/range",
            params={
                "feature_id": feature_id,
                "entity_key": entity_key,
                "start_time": start_time,
                "end_time": end_time
            }
        )
        self.assert_success(range_resp)

        # 4. 验证返回数据的时间顺序和数量
        results = range_resp.result
        assert isinstance(results, list)
        assert len(results) >= 5, "Should return at least 5 records"

        # 验证按时间倒序排列
        for i in range(len(results) - 1):
            assert results[i]['timestamp_ms'] >= results[i + 1]['timestamp_ms']


class TestFeatureStoreConcurrency(BaseTest):
    """并发隔离级别测试"""

    @pytest.mark.concurrency
    async def test_concurrent_feature_registration(self):
        """测试并发特征注册的隔离性"""
        runner = self.create_concurrent_runner()

        # 准备并发请求
        feature_data_list = self.factory.create_batch(self.factory.create_feature_data, count=20)
        requests = [
            ("POST", "/feature-store/features", {"json": fd.to_dict()})
            for fd in feature_data_list
        ]

        # 并发执行
        results = await runner.run_concurrent(requests, max_concurrent=10)

        # 验证所有请求成功
        success_count = sum(1 for r in results if not isinstance(r, Exception) and r.is_success)
        assert success_count == 20, f"Expected 20 successes, got {success_count}"

        # 验证特征名称唯一性
        created_ids = []
        for r in results:
            if not isinstance(r, Exception) and r.is_success:
                feature_id = r.result.get('feature_id')
                if feature_id:
                    created_ids.append(feature_id)
                    self.register_resource("/feature-store/features", feature_id)

        assert len(set(created_ids)) == len(created_ids), "All feature IDs should be unique"

    @pytest.mark.concurrency
    async def test_concurrent_feature_value_ingest(self):
        """测试同一特征的并发写入隔离性"""
        # 注册特征
        feature_data = self.factory.create_feature_data()
        register_resp = await self.client.post(
            "/feature-store/features",
            json=feature_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        feature_id = register_resp.result.get('feature_id')
        self.register_resource("/feature-store/features", feature_id)

        # 准备并发写入
        entity_key = f"concurrent_test_user_{int(time.time())}"
        runner = self.create_concurrent_runner()

        requests = []
        for i in range(50):
            value_data = self.factory.create_feature_value_data(feature_id, entity_key)
            value_dict = value_data.to_dict()
            value_dict['value']['sequence'] = i
            requests.append(("POST", "/feature-store/values/ingest", {"json": value_dict}))

        # 并发执行
        results = await runner.run_concurrent(requests, max_concurrent=20)

        # 验证结果
        success_count = sum(1 for r in results if not isinstance(r, Exception) and r.is_success)
        assert success_count >= 45, f"Expected at least 45 successes, got {success_count}"

        # 验证最终读取成功
        final_resp = await self.client.get(
            "/feature-store/values/latest",
            params={"feature_id": feature_id, "entity_key": entity_key}
        )
        self.assert_success(final_resp)
        assert final_resp.result is not None

    @pytest.mark.concurrency
    async def test_read_write_isolation(self):
        """测试读写隔离"""
        # 注册特征
        feature_data = self.factory.create_feature_data()
        register_resp = await self.client.post(
            "/feature-store/features",
            json=feature_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        feature_id = register_resp.result.get('feature_id')
        self.register_resource("/feature-store/features", feature_id)

        entity_key = f"rw_test_user_{int(time.time())}"

        # 先写入初始值
        initial_value = self.factory.create_feature_value_data(feature_id, entity_key)
        await self.client.post("/feature-store/values/ingest", json=initial_value.to_dict())

        # 并发读写
        runner = self.create_concurrent_runner()
        requests = []

        for i in range(30):
            if i % 2 == 0:
                # 写入
                value_data = self.factory.create_feature_value_data(feature_id, entity_key)
                requests.append(("POST", "/feature-store/values/ingest", {"json": value_data.to_dict()}))
            else:
                # 读取
                requests.append(("GET", "/feature-store/values/latest", {
                    "params": {"feature_id": feature_id, "entity_key": entity_key}
                }))

        results = await runner.run_concurrent(requests, max_concurrent=10)

        # 验证所有操作都成功完成
        for r in results:
            if not isinstance(r, Exception):
                assert r.is_success or r.status_code == 404, f"Unexpected failure: {r.error_message}"


class TestFeatureStoreTimeout(MockBaseTest):
    """超时降级行为测试"""

    @pytest.mark.timeout
    async def test_feature_query_timeout_handling(self):
        """测试特征查询超时的降级处理"""
        # Mock超时响应
        self.mock_timeout("/feature-store/features/timeout_test_id")

        # 发起请求
        response = await self.client.get("/feature-store/features/timeout_test_id")

        # 验证超时响应
        assert response.status_code == 504 or response.code == 504
        assert "timeout" in (response.error_message or "").lower() or \
               "timeout" in (response.message or "").lower()

    @pytest.mark.timeout
    async def test_batch_ingest_timeout(self):
        """测试批量写入超时的降级处理"""
        self.mock_timeout("/feature-store/values/batch-ingest", method="POST")

        batch_data = []
        for i in range(100):
            batch_data.append({
                "feature_id": "test_feature",
                "entity_key": f"user_{i}",
                "value": {"test": "value"}
            })

        response = await self.client.post(
            "/feature-store/values/batch-ingest",
            json=batch_data
        )

        assert response.status_code == 504 or not response.is_success

    @pytest.mark.timeout
    async def test_slow_query_degradation(self):
        """测试慢查询的降级行为"""
        # Mock慢响应
        self.mock_slow_response("/feature-store/features/slow_id", delay_seconds=3.0)

        response, elapsed = await self.measure_performance(
            self.client.get,
            "/feature-store/features/slow_id"
        )

        # 验证响应时间
        assert elapsed >= 1.0, f"Expected slow response, got {elapsed}s"

    @pytest.mark.timeout
    async def test_backfill_job_timeout(self):
        """测试回填任务超时的降级处理"""
        self.mock_timeout("/feature-store/backfill-jobs", method="POST")

        backfill_data = self.factory.create_backfill_job_data("test_feature")
        response = await self.client.post(
            "/feature-store/backfill-jobs",
            json=backfill_data
        )

        # 验证失败时的优雅降级
        assert not response.is_success
        # 验证请求历史被记录
        history = self.client.get_request_history()
        assert len(history) == 1
        assert history[0][1].endswith("/feature-store/backfill-jobs")


class TestFeatureStoreEdgeCases(BaseTest):
    """边界情况测试"""

    @pytest.mark.smoke
    async def test_get_nonexistent_feature(self):
        """测试查询不存在的特征"""
        response = await self.client.get("/feature-store/features/nonexistent_id_12345")
        assert response.status_code == 404 or response.code == 404

    @pytest.mark.smoke
    async def test_register_duplicate_feature_name(self):
        """测试注册重复特征名称"""
        feature_data = self.factory.create_feature_data()

        # 第一次注册
        resp1 = await self.client.post(
            "/feature-store/features",
            json=feature_data.to_dict()
        )
        if resp1.is_success:
            feature_id = resp1.result.get('feature_id')
            self.register_resource("/feature-store/features", feature_id)

        # 第二次注册相同名称
        resp2 = await self.client.post(
            "/feature-store/features",
            json=feature_data.to_dict()
        )

        # 验证返回错误
        assert not resp2.is_success or resp2.code != 201

    async def test_feature_status_transition(self):
        """测试特征状态流转"""
        feature_data = self.factory.create_feature_data()
        register_resp = await self.client.post(
            "/feature-store/features",
            json=feature_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        feature_id = register_resp.result.get('feature_id')
        self.register_resource("/feature-store/features", feature_id)

        # 更新状态为inactive
        update_resp = await self.client.put(
            f"/feature-store/features/{feature_id}/status",
            params={"status": "inactive"}
        )
        self.assert_success(update_resp)

        # 验证状态已更新
        query_resp = await self.client.get(f"/feature-store/features/{feature_id}")
        self.assert_success(query_resp)
        assert query_resp.result.get('status') == 'inactive'

    async def test_delete_feature(self):
        """测试删除特征"""
        feature_data = self.factory.create_feature_data()
        register_resp = await self.client.post(
            "/feature-store/features",
            json=feature_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        feature_id = register_resp.result.get('feature_id')

        # 删除特征
        delete_resp = await self.client.delete(f"/feature-store/features/{feature_id}")
        self.assert_success(delete_resp)

        # 验证删除后查询不到
        query_resp = await self.client.get(f"/feature-store/features/{feature_id}")
        # 应该返回404或者标记为已删除
        assert query_resp.status_code == 404 or not query_resp.is_success
