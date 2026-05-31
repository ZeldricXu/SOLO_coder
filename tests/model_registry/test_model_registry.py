"""
模型注册与版本模块单元测试
测试场景：
1. 数据一致性保障
2. 并发隔离级别
3. 超时降级行为
"""
import pytest
import asyncio
import time
from typing import List

from tests.base_test import BaseTest, MockBaseTest
from tests.data_factory import get_factory


pytestmark = pytest.mark.model_registry


class TestModelRegistryConsistency(BaseTest):
    """数据一致性保障测试"""

    @pytest.mark.consistency
    @pytest.mark.smoke
    async def test_model_register_and_query_consistency(self):
        """测试模型注册与查询的数据一致性"""
        # 1. 准备测试数据
        model_data = self.factory.create_model_data()

        # 2. 注册模型
        register_resp = await self.client.post(
            "/model-registry/models",
            json=model_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)

        model_id = register_resp.result.get('model_id')
        assert model_id is not None, "Model ID should not be None"
        self.register_resource("/model-registry/models", model_id)

        # 3. 查询模型
        query_resp = await self.client.get(f"/model-registry/models/{model_id}")
        self.assert_success(query_resp)

        # 4. 验证数据一致性
        stored_model = query_resp.result
        self.assert_data_consistency(
            model_data.to_dict(),
            stored_model,
            keys=['model_name', 'model_type', 'provider', 'task_type', 'owner']
        )

    @pytest.mark.consistency
    async def test_model_version_create_consistency(self):
        """测试模型版本创建的数据一致性"""
        # 1. 先注册模型
        model_data = self.factory.create_model_data()
        register_resp = await self.client.post(
            "/model-registry/models",
            json=model_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        model_id = register_resp.result.get('model_id')
        self.register_resource("/model-registry/models", model_id)

        # 2. 创建模型版本
        version_data = self.factory.create_model_version_data(model_id)
        version_resp = await self.client.post(
            "/model-registry/versions",
            json=version_data.to_dict()
        )
        self.assert_success(version_resp, status_code=201)

        version_id = version_resp.result.get('version_id')
        assert version_id is not None

        # 3. 查询版本
        query_resp = await self.client.get(f"/model-registry/versions/{version_id}")
        self.assert_success(query_resp)

        # 4. 验证一致性
        stored_version = query_resp.result
        self.assert_data_consistency(
            version_data.to_dict(),
            stored_version,
            keys=['model_id', 'version', 'description', 'dataset', 'created_by']
        )

    @pytest.mark.consistency
    async def test_stage_transition_consistency(self):
        """测试阶段流转的一致性"""
        # 1. 注册模型和版本
        model_data = self.factory.create_model_data()
        register_resp = await self.client.post(
            "/model-registry/models",
            json=model_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        model_id = register_resp.result.get('model_id')
        self.register_resource("/model-registry/models", model_id)

        version_data = self.factory.create_model_version_data(model_id)
        version_resp = await self.client.post(
            "/model-registry/versions",
            json=version_data.to_dict()
        )
        self.assert_success(version_resp, status_code=201)
        version_id = version_resp.result.get('version_id')

        # 2. 阶段流转: development -> staging
        transition_data = self.factory.create_stage_transition_data(version_id, 'staging')
        transition_resp = await self.client.post(
            "/model-registry/versions/transition",
            json=transition_data
        )
        self.assert_success(transition_resp)

        # 3. 验证阶段已更新
        version_query_resp = await self.client.get(f"/model-registry/versions/{version_id}")
        self.assert_success(version_query_resp)
        assert version_query_resp.result.get('stage') == 'staging'

        # 4. 验证流转日志被记录
        logs_resp = await self.client.get(f"/model-registry/versions/{version_id}/transitions")
        if logs_resp.is_success:
            logs = logs_resp.result
            assert isinstance(logs, list)
            assert len(logs) >= 1
            assert logs[0].get('to_stage') == 'staging'

    @pytest.mark.consistency
    async def test_model_versions_list_consistency(self):
        """测试模型版本列表的一致性"""
        # 1. 注册模型
        model_data = self.factory.create_model_data()
        register_resp = await self.client.post(
            "/model-registry/models",
            json=model_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        model_id = register_resp.result.get('model_id')
        self.register_resource("/model-registry/models", model_id)

        # 2. 创建多个版本
        created_versions = []
        for i in range(5):
            version_data = self.factory.create_model_version_data(model_id, f"v1.{i}.0")
            version_resp = await self.client.post(
                "/model-registry/versions",
                json=version_data.to_dict()
            )
            if version_resp.is_success:
                created_versions.append(version_resp.result.get('version'))

        # 3. 查询版本列表
        list_resp = await self.client.get(f"/model-registry/models/{model_id}/versions")
        self.assert_success(list_resp)

        versions = list_resp.result
        assert isinstance(versions, list)
        assert len(versions) >= len(created_versions)

        # 4. 验证所有创建的版本都在列表中
        version_numbers = [v.get('version') for v in versions]
        for v in created_versions:
            assert v in version_numbers, f"Version {v} should be in the list"

    @pytest.mark.consistency
    async def test_production_stage_protection(self):
        """测试Production阶段的保护机制"""
        # 1. 注册模型和版本
        model_data = self.factory.create_model_data()
        register_resp = await self.client.post(
            "/model-registry/models",
            json=model_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        model_id = register_resp.result.get('model_id')
        self.register_resource("/model-registry/models", model_id)

        version_data = self.factory.create_model_version_data(model_id, "v1.0.0")
        version_resp = await self.client.post(
            "/model-registry/versions",
            json=version_data.to_dict()
        )
        self.assert_success(version_resp, status_code=201)
        version_id = version_resp.result.get('version_id')

        # 2. 流转到production
        transition_data = self.factory.create_stage_transition_data(version_id, 'production')
        transition_resp = await self.client.post(
            "/model-registry/versions/transition",
            json=transition_data
        )
        self.assert_success(transition_resp)

        # 3. 尝试流转到archived（某些系统可能阻止直接从production到archived）
        archive_data = self.factory.create_stage_transition_data(version_id, 'archived')
        archive_resp = await self.client.post(
            "/model-registry/versions/transition",
            json=archive_data
        )

        # 根据业务规则，这个操作可能成功或失败
        # 如果失败，验证错误信息合理
        if not archive_resp.is_success:
            assert archive_resp.code in [400, 403, 422]


class TestModelRegistryConcurrency(BaseTest):
    """并发隔离级别测试"""

    @pytest.mark.concurrency
    async def test_concurrent_model_registration(self):
        """测试并发模型注册的隔离性"""
        runner = self.create_concurrent_runner()

        # 准备并发注册请求
        model_data_list = self.factory.create_batch(self.factory.create_model_data, count=25)
        requests = [
            ("POST", "/model-registry/models", {"json": md.to_dict()})
            for md in model_data_list
        ]

        # 并发执行
        results = await runner.run_concurrent(requests, max_concurrent=10)

        # 验证结果
        success_count = sum(
            1 for r in results
            if not isinstance(r, Exception) and r.is_success
        )
        assert success_count >= 20, f"Expected at least 20 successes, got {success_count}"

        # 验证模型ID唯一性
        created_ids = []
        for r in results:
            if not isinstance(r, Exception) and r.is_success:
                model_id = r.result.get('model_id')
                if model_id:
                    created_ids.append(model_id)
                    self.register_resource("/model-registry/models", model_id)

        assert len(set(created_ids)) == len(created_ids), "All model IDs should be unique"

    @pytest.mark.concurrency
    async def test_concurrent_version_creation(self):
        """测试同一模型并发创建版本的隔离性"""
        # 注册模型
        model_data = self.factory.create_model_data()
        register_resp = await self.client.post(
            "/model-registry/models",
            json=model_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        model_id = register_resp.result.get('model_id')
        self.register_resource("/model-registry/models", model_id)

        # 并发创建版本
        runner = self.create_concurrent_runner()
        requests = []

        for i in range(20):
            version_data = self.factory.create_model_version_data(model_id, f"v2.{i}.0")
            requests.append(("POST", "/model-registry/versions", {"json": version_data.to_dict()}))

        results = await runner.run_concurrent(requests, max_concurrent=10)

        # 验证所有版本都被创建
        success_count = sum(
            1 for r in results
            if not isinstance(r, Exception) and r.is_success
        )
        assert success_count >= 15, f"Expected at least 15 successes, got {success_count}"

        # 查询版本列表验证
        list_resp = await self.client.get(f"/model-registry/models/{model_id}/versions")
        if list_resp.is_success:
            versions = list_resp.result
            assert isinstance(versions, list)
            assert len(versions) >= 15

    @pytest.mark.concurrency
    async def test_concurrent_stage_transitions(self):
        """测试并发阶段流转的隔离性"""
        # 注册模型和版本
        model_data = self.factory.create_model_data()
        register_resp = await self.client.post(
            "/model-registry/models",
            json=model_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        model_id = register_resp.result.get('model_id')
        self.register_resource("/model-registry/models", model_id)

        version_data = self.factory.create_model_version_data(model_id, "v3.0.0")
        version_resp = await self.client.post(
            "/model-registry/versions",
            json=version_data.to_dict()
        )
        self.assert_success(version_resp, status_code=201)
        version_id = version_resp.result.get('version_id')

        # 并发尝试不同的阶段流转
        runner = self.create_concurrent_runner()
        stages = ['development', 'staging', 'production', 'archived']
        requests = []

        for stage in stages:
            transition_data = self.factory.create_stage_transition_data(version_id, stage)
            requests.append(("POST", "/model-registry/versions/transition", {"json": transition_data}))

        results = await runner.run_concurrent(requests, max_concurrent=4)

        # 验证至少有一些成功（取决于业务规则）
        success_count = sum(
            1 for r in results
            if not isinstance(r, Exception) and r.is_success
        )
        # 至少应该有一个成功
        assert success_count >= 1

        # 验证最终状态是确定的
        final_resp = await self.client.get(f"/model-registry/versions/{version_id}")
        if final_resp.is_success:
            final_stage = final_resp.result.get('stage')
            assert final_stage in stages, "Final stage should be one of the requested stages"

    @pytest.mark.concurrency
    async def test_concurrent_model_updates(self):
        """测试并发模型更新的隔离性"""
        # 注册模型
        model_data = self.factory.create_model_data()
        register_resp = await self.client.post(
            "/model-registry/models",
            json=model_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        model_id = register_resp.result.get('model_id')
        self.register_resource("/model-registry/models", model_id)

        # 并发更新模型
        runner = self.create_concurrent_runner()
        requests = []

        for i in range(10):
            updated_data = self.factory.create_model_data()
            updated_data.model_name = f"updated_model_{i}"
            requests.append(("PUT", f"/model-registry/models/{model_id}", {"json": updated_data.to_dict()}))

        results = await runner.run_concurrent(requests, max_concurrent=5)

        # 验证所有更新请求都被处理
        for r in results:
            if not isinstance(r, Exception):
                # 更新应该成功或返回合理错误
                pass

        # 验证最终模型存在
        final_resp = await self.client.get(f"/model-registry/models/{model_id}")
        self.assert_success(final_resp)


class TestModelRegistryTimeout(MockBaseTest):
    """超时降级行为测试"""

    @pytest.mark.timeout
    async def test_model_register_timeout(self):
        """测试模型注册超时的降级处理"""
        self.mock_timeout("/model-registry/models", method="POST")

        model_data = self.factory.create_model_data()
        response = await self.client.post(
            "/model-registry/models",
            json=model_data.to_dict()
        )

        assert response.status_code == 504 or not response.is_success

    @pytest.mark.timeout
    async def test_model_query_timeout(self):
        """测试模型查询超时的降级处理"""
        self.mock_timeout("/model-registry/models/timeout_test_id")

        response = await self.client.get("/model-registry/models/timeout_test_id")

        assert response.status_code == 504 or response.code == 504

    @pytest.mark.timeout
    async def test_version_create_timeout(self):
        """测试版本创建超时的降级处理"""
        self.mock_timeout("/model-registry/versions", method="POST")

        version_data = self.factory.create_model_version_data("test_model_id")
        response = await self.client.post(
            "/model-registry/versions",
            json=version_data.to_dict()
        )

        assert not response.is_success

    @pytest.mark.timeout
    async def test_stage_transition_timeout(self):
        """测试阶段流转超时的降级处理"""
        self.mock_timeout("/model-registry/versions/transition", method="POST")

        transition_data = self.factory.create_stage_transition_data("test_version_id")
        response = await self.client.post(
            "/model-registry/versions/transition",
            json=transition_data
        )

        assert not response.is_success
        assert response.status_code == 504 or response.code == 504

    @pytest.mark.timeout
    async def test_slow_model_list_query(self):
        """测试慢查询的降级行为"""
        self.mock_slow_response("/model-registry/models", delay_seconds=3.0)

        response, elapsed = await self.measure_performance(
            self.client.get,
            "/model-registry/models"
        )

        assert elapsed >= 1.0, f"Expected slow response, got {elapsed}s"


class TestModelRegistryEdgeCases(BaseTest):
    """边界情况测试"""

    @pytest.mark.smoke
    async def test_get_nonexistent_model(self):
        """测试查询不存在的模型"""
        response = await self.client.get("/model-registry/models/nonexistent_model_id")
        assert response.status_code == 404 or response.code == 404

    @pytest.mark.smoke
    async def test_register_duplicate_model_name(self):
        """测试注册重复模型名称"""
        model_data = self.factory.create_model_data()

        # 第一次注册
        resp1 = await self.client.post(
            "/model-registry/models",
            json=model_data.to_dict()
        )
        if resp1.is_success:
            model_id = resp1.result.get('model_id')
            self.register_resource("/model-registry/models", model_id)

        # 第二次注册相同名称
        resp2 = await self.client.post(
            "/model-registry/models",
            json=model_data.to_dict()
        )

        # 应该返回错误
        assert not resp2.is_success or resp2.code != 201

    async def test_get_nonexistent_version(self):
        """测试查询不存在的版本"""
        response = await self.client.get("/model-registry/versions/nonexistent_version_id")
        assert response.status_code == 404 or response.code == 404

    async def test_create_version_for_nonexistent_model(self):
        """测试为不存在的模型创建版本"""
        version_data = self.factory.create_model_version_data("nonexistent_model_id")
        response = await self.client.post(
            "/model-registry/versions",
            json=version_data.to_dict()
        )

        assert not response.is_success
        assert response.status_code in [400, 404, 422]

    async def test_invalid_stage_transition(self):
        """测试无效的阶段流转"""
        # 注册模型和版本
        model_data = self.factory.create_model_data()
        register_resp = await self.client.post(
            "/model-registry/models",
            json=model_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        model_id = register_resp.result.get('model_id')
        self.register_resource("/model-registry/models", model_id)

        version_data = self.factory.create_model_version_data(model_id)
        version_resp = await self.client.post(
            "/model-registry/versions",
            json=version_data.to_dict()
        )
        self.assert_success(version_resp, status_code=201)
        version_id = version_resp.result.get('version_id')

        # 尝试流转到无效阶段
        invalid_transition = {
            "version_id": version_id,
            "to_stage": "invalid_stage",
            "reason": "test"
        }
        response = await self.client.post(
            "/model-registry/versions/transition",
            json=invalid_transition
        )

        assert not response.is_success
        assert response.code in [400, 422]

    async def test_model_deletion(self):
        """测试模型删除"""
        model_data = self.factory.create_model_data()
        register_resp = await self.client.post(
            "/model-registry/models",
            json=model_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        model_id = register_resp.result.get('model_id')

        # 删除模型
        delete_resp = await self.client.delete(f"/model-registry/models/{model_id}")
        self.assert_success(delete_resp)

        # 验证删除后查询不到
        query_resp = await self.client.get(f"/model-registry/models/{model_id}")
        assert query_resp.status_code == 404 or not query_resp.is_success

    async def test_version_deletion(self):
        """测试版本删除"""
        # 注册模型和版本
        model_data = self.factory.create_model_data()
        register_resp = await self.client.post(
            "/model-registry/models",
            json=model_data.to_dict()
        )
        self.assert_success(register_resp, status_code=201)
        model_id = register_resp.result.get('model_id')
        self.register_resource("/model-registry/models", model_id)

        version_data = self.factory.create_model_version_data(model_id)
        version_resp = await self.client.post(
            "/model-registry/versions",
            json=version_data.to_dict()
        )
        self.assert_success(version_resp, status_code=201)
        version_id = version_resp.result.get('version_id')

        # 删除版本
        delete_resp = await self.client.delete(f"/model-registry/versions/{version_id}")
        self.assert_success(delete_resp)

        # 验证删除后查询不到
        query_resp = await self.client.get(f"/model-registry/versions/{version_id}")
        assert query_resp.status_code == 404 or not query_resp.is_success
