"""
文档解析管道模块单元测试
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


pytestmark = pytest.mark.document_pipeline


class TestDocumentPipelineConsistency(BaseTest):
    """数据一致性保障测试"""

    @pytest.mark.consistency
    @pytest.mark.smoke
    async def test_document_upload_and_query_consistency(self):
        """测试文档上传与查询的数据一致性"""
        # 1. 准备测试数据
        doc_data = self.factory.create_document_data(file_type='md')

        # 2. 上传文档
        upload_resp = await self.client.post(
            "/documents",
            json=doc_data.to_dict()
        )
        self.assert_success(upload_resp, status_code=201)

        document_id = upload_resp.result.get('document_id')
        assert document_id is not None, "Document ID should not be None"
        self.register_resource("/documents", document_id)

        # 3. 查询文档
        query_resp = await self.client.get(f"/documents/{document_id}")
        self.assert_success(query_resp)

        # 4. 验证数据一致性
        stored_doc = query_resp.result
        self.assert_data_consistency(
            doc_data.to_dict(),
            stored_doc,
            keys=['title', 'file_name', 'file_type', 'created_by']
        )

    @pytest.mark.consistency
    async def test_document_parse_consistency(self):
        """测试文档解析结果的一致性"""
        # 1. 上传文档
        doc_data = self.factory.create_document_data(file_type='txt', with_content=True)
        upload_resp = await self.client.post(
            "/documents",
            json=doc_data.to_dict()
        )
        self.assert_success(upload_resp, status_code=201)
        document_id = upload_resp.result.get('document_id')
        self.register_resource("/documents", document_id)

        # 2. 发起解析
        parse_resp = await self.client.post(
            f"/documents/{document_id}/parse",
            json={"content": doc_data.content}
        )
        self.assert_success(parse_resp, status_code=201)

        task_id = parse_resp.result.get('task_id')
        assert task_id is not None

        # 3. 轮询任务状态直到完成
        max_wait = 30
        start_time = time.time()
        while time.time() - start_time < max_wait:
            task_resp = await self.client.get(f"/documents/tasks/{task_id}")
            if task_resp.is_success:
                status = task_resp.result.get('status')
                if status in ['success', 'completed', 'failed']:
                    break
            await asyncio.sleep(1)

        # 4. 获取切片结果
        chunks_resp = await self.client.get(f"/documents/{document_id}/chunks")
        self.assert_success(chunks_resp)

        chunks = chunks_resp.result
        assert isinstance(chunks, list)

        # 5. 验证切片一致性
        if len(chunks) > 0:
            # 验证所有切片都属于该文档
            for chunk in chunks:
                assert chunk.get('document_id') == document_id

            # 验证切片索引连续
            indices = sorted([chunk.get('chunk_index') for chunk in chunks])
            assert indices == list(range(len(chunks))), "Chunk indices should be consecutive"

            # 验证切片内容完整性
            total_content = ''.join([chunk.get('content', '') for chunk in chunks])
            assert len(total_content) > 0, "Total chunk content should not be empty"

    @pytest.mark.consistency
    async def test_large_document_parse_consistency(self):
        """测试大文档解析的一致性"""
        # 创建大文档
        large_content = "\n\n".join([
            f"这是第 {i} 段测试内容。" * 100
            for i in range(50)
        ])

        doc_data = self.factory.create_document_data(file_type='txt')
        doc_data.content = large_content
        doc_data.file_size = len(large_content.encode('utf-8'))

        upload_resp = await self.client.post(
            "/documents",
            json=doc_data.to_dict()
        )
        self.assert_success(upload_resp, status_code=201)
        document_id = upload_resp.result.get('document_id')
        self.register_resource("/documents", document_id)

        # 发起解析
        parse_resp = await self.client.post(
            f"/documents/{document_id}/parse",
            json={"content": large_content}
        )
        self.assert_success(parse_resp, status_code=201)

        task_id = parse_resp.result.get('task_id')

        # 等待解析完成
        max_wait = 60
        start_time = time.time()
        while time.time() - start_time < max_wait:
            task_resp = await self.client.get(f"/documents/tasks/{task_id}")
            if task_resp.is_success:
                status = task_resp.result.get('status')
                progress = task_resp.result.get('progress', 0)
                if status in ['success', 'completed', 'failed']:
                    break
            await asyncio.sleep(2)

        # 验证切片数量合理
        chunks_resp = await self.client.get(f"/documents/{document_id}/chunks")
        self.assert_success(chunks_resp)
        chunks = chunks_resp.result
        assert isinstance(chunks, list)
        assert len(chunks) > 10, "Large document should produce multiple chunks"

    @pytest.mark.consistency
    async def test_multiple_file_types_parsing(self):
        """测试多种文件类型解析的一致性"""
        file_types = ['txt', 'md', 'html', 'json']

        for file_type in file_types:
            doc_data = self.factory.create_document_data(file_type=file_type)
            upload_resp = await self.client.post(
                "/documents",
                json=doc_data.to_dict()
            )
            if upload_resp.is_success:
                document_id = upload_resp.result.get('document_id')
                self.register_resource("/documents", document_id)

                # 验证文件类型被正确存储
                query_resp = await self.client.get(f"/documents/{document_id}")
                if query_resp.is_success:
                    assert query_resp.result.get('file_type') == file_type


class TestDocumentPipelineConcurrency(BaseTest):
    """并发隔离级别测试"""

    @pytest.mark.concurrency
    async def test_concurrent_document_upload(self):
        """测试并发文档上传的隔离性"""
        runner = self.create_concurrent_runner()

        # 准备并发上传
        doc_data_list = self.factory.create_batch(self.factory.create_document_data, count=30)
        requests = [
            ("POST", "/documents", {"json": dd.to_dict()})
            for dd in doc_data_list
        ]

        # 并发执行
        results = await runner.run_concurrent(requests, max_concurrent=10)

        # 验证结果
        success_count = sum(
            1 for r in results
            if not isinstance(r, Exception) and r.is_success
        )
        assert success_count >= 25, f"Expected at least 25 successes, got {success_count}"

        # 验证所有创建的文档ID唯一
        created_ids = []
        for r in results:
            if not isinstance(r, Exception) and r.is_success:
                doc_id = r.result.get('document_id')
                if doc_id:
                    created_ids.append(doc_id)
                    self.register_resource("/documents", doc_id)

        assert len(set(created_ids)) == len(created_ids), "All document IDs should be unique"

    @pytest.mark.concurrency
    async def test_concurrent_document_parsing(self):
        """测试并发文档解析的隔离性"""
        # 先上传多个文档
        document_ids = []
        for i in range(10):
            doc_data = self.factory.create_document_data(file_type='txt', with_content=True)
            upload_resp = await self.client.post(
                "/documents",
                json=doc_data.to_dict()
            )
            if upload_resp.is_success:
                doc_id = upload_resp.result.get('document_id')
                document_ids.append((doc_id, doc_data.content))
                self.register_resource("/documents", doc_id)

        # 并发发起解析
        runner = self.create_concurrent_runner()
        requests = [
            ("POST", f"/documents/{doc_id}/parse", {"json": {"content": content}})
            for doc_id, content in document_ids
        ]

        results = await runner.run_concurrent(requests, max_concurrent=5)

        # 验证所有解析任务都被创建
        success_count = sum(
            1 for r in results
            if not isinstance(r, Exception) and r.is_success
        )
        assert success_count == len(document_ids), "All parse tasks should be created"

    @pytest.mark.concurrency
    async def test_concurrent_chunk_queries(self):
        """测试并发查询文档切片的隔离性"""
        # 上传并解析一个文档
        doc_data = self.factory.create_document_data(file_type='md', with_content=True)
        upload_resp = await self.client.post(
            "/documents",
            json=doc_data.to_dict()
        )
        self.assert_success(upload_resp, status_code=201)
        document_id = upload_resp.result.get('document_id')
        self.register_resource("/documents", document_id)

        # 发起解析
        parse_resp = await self.client.post(
            f"/documents/{document_id}/parse",
            json={"content": doc_data.content}
        )
        self.assert_success(parse_resp, status_code=201)

        # 等待解析完成
        await asyncio.sleep(5)

        # 并发查询切片
        runner = self.create_concurrent_runner()
        requests = [
            ("GET", f"/documents/{document_id}/chunks", {})
            for _ in range(50)
        ]

        results = await runner.run_concurrent(requests, max_concurrent=20)

        # 验证所有查询成功且结果一致
        success_results = [
            r for r in results
            if not isinstance(r, Exception) and r.is_success
        ]
        assert len(success_results) >= 45, "Most queries should succeed"

        # 验证数据一致性
        if success_results:
            reference_chunks = success_results[0].result
            for r in success_results[1:]:
                assert r.result == reference_chunks, "All queries should return same result"


class TestDocumentPipelineTimeout(MockBaseTest):
    """超时降级行为测试"""

    @pytest.mark.timeout
    async def test_document_upload_timeout(self):
        """测试文档上传超时的降级处理"""
        self.mock_timeout("/documents", method="POST")

        doc_data = self.factory.create_document_data()
        response = await self.client.post(
            "/documents",
            json=doc_data.to_dict()
        )

        assert response.status_code == 504 or not response.is_success

    @pytest.mark.timeout
    async def test_parse_task_timeout(self):
        """测试解析任务超时的降级处理"""
        self.mock_timeout("/documents/test_doc_id/parse", method="POST")

        response = await self.client.post(
            "/documents/test_doc_id/parse",
            json={"content": "test content"}
        )

        assert not response.is_success
        assert response.status_code == 504 or response.code == 504

    @pytest.mark.timeout
    async def test_slow_document_query(self):
        """测试慢查询的降级行为"""
        self.mock_slow_response("/documents/slow_doc_id", delay_seconds=2.5)

        response, elapsed = await self.measure_performance(
            self.client.get,
            "/documents/slow_doc_id"
        )

        assert elapsed >= 1.0, f"Expected slow response, got {elapsed}s"

    @pytest.mark.timeout
    async def test_chunk_query_timeout(self):
        """测试切片查询超时的降级"""
        self.mock_timeout("/documents/test_doc_id/chunks")

        response = await self.client.get("/documents/test_doc_id/chunks")

        assert not response.is_success
        # 验证请求被正确记录
        history = self.client.get_request_history()
        assert any('/chunks' in req[1] for req in history)


class TestDocumentPipelineEdgeCases(BaseTest):
    """边界情况测试"""

    @pytest.mark.smoke
    async def test_get_nonexistent_document(self):
        """测试查询不存在的文档"""
        response = await self.client.get("/documents/nonexistent_doc_id")
        assert response.status_code == 404 or response.code == 404

    @pytest.mark.smoke
    async def test_empty_document_upload(self):
        """测试空文档上传"""
        doc_data = self.factory.create_document_data(file_type='txt', with_content=False)
        doc_data.content = ""
        doc_data.file_size = 0

        response = await self.client.post(
            "/documents",
            json=doc_data.to_dict()
        )

        # 空文档应该被接受或返回合理的错误
        assert response.is_success or response.code in [400, 422]

    async def test_document_status_update(self):
        """测试文档状态更新"""
        doc_data = self.factory.create_document_data()
        upload_resp = await self.client.post(
            "/documents",
            json=doc_data.to_dict()
        )
        self.assert_success(upload_resp, status_code=201)
        document_id = upload_resp.result.get('document_id')
        self.register_resource("/documents", document_id)

        # 更新状态
        update_resp = await self.client.put(
            f"/documents/{document_id}/status",
            params={"status": "processing"}
        )
        self.assert_success(update_resp)

        # 验证状态更新
        query_resp = await self.client.get(f"/documents/{document_id}")
        if query_resp.is_success:
            assert query_resp.result.get('status') == 'processing'

    async def test_large_file_upload(self):
        """测试大文件上传"""
        # 创建大内容
        large_content = "X" * 1000000  # 1MB content

        doc_data = self.factory.create_document_data(file_type='txt')
        doc_data.content = large_content
        doc_data.file_size = len(large_content.encode('utf-8'))

        response = await self.client.post(
            "/documents",
            json=doc_data.to_dict(),
            timeout=60
        )

        # 大文件应该被接受或返回明确的错误
        assert response.is_success or response.code in [413, 400]

    async def test_parse_nonexistent_document(self):
        """测试解析不存在的文档"""
        response = await self.client.post(
            "/documents/nonexistent_doc/parse",
            json={"content": "test"}
        )

        assert not response.is_success
        assert response.status_code in [404, 400, 500]

    async def test_task_query_nonexistent(self):
        """测试查询不存在的解析任务"""
        response = await self.client.get("/documents/tasks/nonexistent_task_id")
        assert response.status_code == 404 or response.code == 404
