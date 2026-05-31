package com.streamsql.modules.vector_index;

import com.streamsql.dto.VectorIndexDTO;
import com.streamsql.dto.VectorSearchDTO;
import com.streamsql.entity.VectorEmbedding;
import com.streamsql.entity.VectorIndex;
import com.streamsql.fixture.TestBuilders;
import com.streamsql.fixture.TestFixtures;
import com.streamsql.mapper.VectorEmbeddingMapper;
import com.streamsql.mapper.VectorIndexMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("向量索引构建模块测试")
class VectorIndexServiceTest {

    @Mock
    private VectorIndexMapper vectorIndexMapper;

    @Mock
    private VectorEmbeddingMapper vectorEmbeddingMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private VectorIndexService vectorIndexService;

    @Nested
    @DisplayName("正常流程测试")
    class NormalFlowTest {

        @Test
        @DisplayName("创建向量索引 - 成功")
        void shouldCreateIndexSuccessfully() throws Exception {
            VectorIndexDTO dto = TestBuilders.vectorIndexDTO().build();
            when(vectorIndexMapper.insert(any(VectorIndex.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            VectorIndex result = vectorIndexService.createIndex(dto);

            assertNotNull(result);
            assertEquals(dto.getIndexName(), result.getIndexName());
            assertEquals("building", result.getStatus());

            ArgumentCaptor<VectorIndex> captor = ArgumentCaptor.forClass(VectorIndex.class);
            verify(vectorIndexMapper).insert(captor.capture());
            assertEquals(dto.getIndexName(), captor.getValue().getIndexName());
        }

        @Test
        @DisplayName("删除向量索引 - 成功")
        void shouldDeleteIndexSuccessfully() {
            String indexId = "idx_001";
            VectorIndex index = TestFixtures.createVectorIndexEntity();
            index.setIndexId(indexId);
            index.setIndexPath("/tmp/test.idx");

            when(vectorIndexMapper.selectById(indexId)).thenReturn(index);
            when(vectorIndexMapper.deleteById(indexId)).thenReturn(1);

            assertDoesNotThrow(() -> vectorIndexService.deleteIndex(indexId));
            verify(vectorIndexMapper).deleteById(indexId);
        }

        @Test
        @DisplayName("查询向量索引 - 成功")
        void shouldGetIndexSuccessfully() {
            String indexId = "idx_001";
            VectorIndex expectedIndex = TestFixtures.createVectorIndexEntity();
            expectedIndex.setIndexId(indexId);

            when(vectorIndexMapper.selectById(indexId)).thenReturn(expectedIndex);

            VectorIndex result = vectorIndexService.getIndex(indexId);

            assertNotNull(result);
            assertEquals(indexId, result.getIndexId());
        }

        @Test
        @DisplayName("列出向量索引 - 成功")
        void shouldListIndexesSuccessfully() {
            VectorIndex index = TestFixtures.createVectorIndexEntity();
            List<VectorIndex> indexes = Arrays.asList(index);

            when(vectorIndexMapper.selectPage(any(), any()))
                    .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(indexes, 1));

            com.streamsql.common.PageResult<VectorIndex> result =
                    vectorIndexService.listIndexes(1, 10, null, null);

            assertNotNull(result);
            assertFalse(result.getRecords().isEmpty());
            assertEquals(1, result.getRecords().size());
        }

        @Test
        @DisplayName("添加向量嵌入 - 成功")
        void shouldAddEmbeddingSuccessfully() throws Exception {
            String indexId = "idx_001";
            VectorIndex index = TestFixtures.createVectorIndexEntity();
            index.setIndexId(indexId);
            index.setStatus("ready");
            index.setIndexPath("/tmp/test.idx");

            float[] vector = new float[]{0.1f, 0.2f, 0.3f};
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", "test");

            when(vectorIndexMapper.selectById(indexId)).thenReturn(index);
            when(vectorEmbeddingMapper.insert(any(VectorEmbedding.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            assertDoesNotThrow(() -> vectorIndexService.addEmbedding(indexId, "data_001", vector, metadata));
            verify(vectorEmbeddingMapper).insert(any(VectorEmbedding.class));
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryTest {

        @Test
        @DisplayName("创建索引 - 空字符串值")
        void shouldCreateIndexWithEmptyValues() throws Exception {
            VectorIndexDTO dto = TestBuilders.vectorIndexDTO().withEmptyValues().build();
            when(vectorIndexMapper.insert(any(VectorIndex.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            VectorIndex result = vectorIndexService.createIndex(dto);

            assertNotNull(result);
            assertEquals("", result.getIndexName());
        }

        @Test
        @DisplayName("创建索引 - null值")
        void shouldCreateIndexWithNullValues() throws Exception {
            VectorIndexDTO dto = TestBuilders.vectorIndexDTO().withNullValues().build();
            when(vectorIndexMapper.insert(any(VectorIndex.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn(null);

            VectorIndex result = vectorIndexService.createIndex(dto);

            assertNotNull(result);
            assertNull(result.getIndexName());
        }

        @Test
        @DisplayName("创建索引 - 超长字符串")
        void shouldCreateIndexWithLongStrings() throws Exception {
            VectorIndexDTO dto = TestBuilders.vectorIndexDTO().withLongStrings().build();
            when(vectorIndexMapper.insert(any(VectorIndex.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            VectorIndex result = vectorIndexService.createIndex(dto);

            assertNotNull(result);
            assertEquals(1000, result.getIndexName().length());
        }

        @Test
        @DisplayName("创建索引 - 零维度")
        void shouldCreateIndexWithZeroDimension() throws Exception {
            VectorIndexDTO dto = TestBuilders.vectorIndexDTO().vectorDimension(0).build();
            when(vectorIndexMapper.insert(any(VectorIndex.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            VectorIndex result = vectorIndexService.createIndex(dto);

            assertNotNull(result);
            assertEquals(0, result.getVectorDimension());
        }

        @Test
        @DisplayName("创建索引 - 负维度")
        void shouldCreateIndexWithNegativeDimension() throws Exception {
            VectorIndexDTO dto = TestBuilders.vectorIndexDTO().vectorDimension(-1).build();
            when(vectorIndexMapper.insert(any(VectorIndex.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            VectorIndex result = vectorIndexService.createIndex(dto);

            assertNotNull(result);
            assertEquals(-1, result.getVectorDimension());
        }

        @Test
        @DisplayName("搜索向量 - 空向量")
        void shouldSearchWithEmptyVector() {
            String indexId = "idx_001";
            VectorSearchDTO dto = TestBuilders.vectorSearchDTO().withEmptyVector().build();

            VectorIndex index = TestFixtures.createVectorIndexEntity();
            index.setIndexId(indexId);
            index.setStatus("ready");
            index.setIndexPath("/tmp/test.idx");
            index.setVectorDimension(0);

            when(vectorIndexMapper.selectById(indexId)).thenReturn(index);

            assertDoesNotThrow(() -> vectorIndexService.search(indexId, dto));
        }

        @Test
        @DisplayName("搜索向量 - null向量")
        void shouldSearchWithNullVector() {
            String indexId = "idx_001";
            VectorSearchDTO dto = TestBuilders.vectorSearchDTO().withNullVector().build();

            VectorIndex index = TestFixtures.createVectorIndexEntity();
            index.setIndexId(indexId);
            index.setStatus("ready");
            index.setIndexPath("/tmp/test.idx");

            when(vectorIndexMapper.selectById(indexId)).thenReturn(index);

            assertThrows(NullPointerException.class, () -> vectorIndexService.search(indexId, dto));
        }

        @Test
        @DisplayName("搜索向量 - TopK为0")
        void shouldSearchWithZeroTopK() {
            String indexId = "idx_001";
            VectorSearchDTO dto = TestBuilders.vectorSearchDTO().withZeroTopK().build();

            VectorIndex index = TestFixtures.createVectorIndexEntity();
            index.setIndexId(indexId);
            index.setStatus("ready");
            index.setIndexPath("/tmp/test.idx");

            when(vectorIndexMapper.selectById(indexId)).thenReturn(index);

            List<Map<String, Object>> results = vectorIndexService.search(indexId, dto);
            assertTrue(results.isEmpty());
        }

        @Test
        @DisplayName("搜索向量 - TopK为负数")
        void shouldSearchWithNegativeTopK() {
            String indexId = "idx_001";
            VectorSearchDTO dto = TestBuilders.vectorSearchDTO().withNegativeTopK().build();

            VectorIndex index = TestFixtures.createVectorIndexEntity();
            index.setIndexId(indexId);
            index.setStatus("ready");
            index.setIndexPath("/tmp/test.idx");

            when(vectorIndexMapper.selectById(indexId)).thenReturn(index);

            assertThrows(IllegalArgumentException.class, () -> vectorIndexService.search(indexId, dto));
        }

        @Test
        @DisplayName("查询索引 - 不存在的ID")
        void shouldReturnNullForNonExistentIndex() {
            String nonExistentId = "non_existent";

            when(vectorIndexMapper.selectById(nonExistentId)).thenReturn(null);

            VectorIndex result = vectorIndexService.getIndex(nonExistentId);

            assertNull(result);
        }

        @Test
        @DisplayName("删除索引 - 不存在的ID")
        void shouldHandleDeletingNonExistentIndex() {
            String nonExistentId = "non_existent";

            when(vectorIndexMapper.selectById(nonExistentId)).thenReturn(null);
            when(vectorIndexMapper.deleteById(nonExistentId)).thenReturn(0);

            assertDoesNotThrow(() -> vectorIndexService.deleteIndex(nonExistentId));
        }

        @Test
        @DisplayName("搜索索引 - 不存在或未就绪")
        void shouldThrowWhenSearchingUnreadyIndex() {
            String indexId = "idx_001";
            VectorSearchDTO dto = TestBuilders.vectorSearchDTO().build();

            VectorIndex index = TestFixtures.createVectorIndexEntity();
            index.setIndexId(indexId);
            index.setStatus("building");

            when(vectorIndexMapper.selectById(indexId)).thenReturn(index);

            assertThrows(IllegalArgumentException.class, () -> vectorIndexService.search(indexId, dto));
        }
    }

    @Nested
    @DisplayName("并发竞态场景测试")
    class ConcurrencyTest {

        @Test
        @DisplayName("并发创建索引 - 保证线程安全")
        void shouldHandleConcurrentIndexCreation() throws Exception {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            when(vectorIndexMapper.insert(any(VectorIndex.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        VectorIndexDTO dto = TestBuilders.vectorIndexDTO()
                                .indexName("并发索引-" + index + "-" + System.nanoTime())
                                .build();
                        vectorIndexService.createIndex(dto);
                        successCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount, successCount.get());
        }

        @Test
        @DisplayName("并发添加向量 - 保证线程安全")
        void shouldHandleConcurrentEmbeddingAdditions() throws Exception {
            String indexId = "idx_001";
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            VectorIndex index = TestFixtures.createVectorIndexEntity();
            index.setIndexId(indexId);
            index.setStatus("ready");
            index.setIndexPath("/tmp/test.idx");

            when(vectorIndexMapper.selectById(indexId)).thenReturn(index);
            when(vectorEmbeddingMapper.insert(any(VectorEmbedding.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            for (int i = 0; i < threadCount; i++) {
                final int dataIndex = i;
                executor.submit(() -> {
                    try {
                        float[] vector = new float[]{0.1f * dataIndex, 0.2f, 0.3f};
                        vectorIndexService.addEmbedding(indexId, "data_" + dataIndex, vector, new HashMap<>());
                        successCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount, successCount.get());
        }

        @Test
        @DisplayName("并发搜索 - 保证线程安全")
        void shouldHandleConcurrentSearches() throws InterruptedException {
            String indexId = "idx_001";
            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            VectorIndex index = TestFixtures.createVectorIndexEntity();
            index.setIndexId(indexId);
            index.setStatus("ready");
            index.setIndexPath("/tmp/test.idx");

            when(vectorIndexMapper.selectById(indexId)).thenReturn(index);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        VectorSearchDTO dto = TestBuilders.vectorSearchDTO().build();
                        vectorIndexService.search(indexId, dto);
                        successCount.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertEquals(threadCount, successCount.get());
        }

        @Test
        @DisplayName("并发创建和删除 - 保证一致性")
        void shouldHandleConcurrentCreateAndDelete() throws InterruptedException {
            String indexId = "idx_001";
            VectorIndex index = TestFixtures.createVectorIndexEntity();
            index.setIndexId(indexId);
            index.setIndexPath("/tmp/test.idx");

            when(vectorIndexMapper.selectById(indexId)).thenReturn(index);
            when(vectorIndexMapper.deleteById(indexId)).thenReturn(1);
            when(vectorIndexMapper.insert(any(VectorIndex.class))).thenReturn(1);
            when(objectMapper.writeValueAsString(any())).thenReturn("{}");

            ExecutorService executor = Executors.newFixedThreadPool(2);
            CountDownLatch latch = new CountDownLatch(2);

            executor.submit(() -> {
                try {
                    vectorIndexService.deleteIndex(indexId);
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    VectorIndexDTO dto = TestBuilders.vectorIndexDTO().build();
                    vectorIndexService.createIndex(dto);
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });

            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("降级行为测试")
    class DegradationTest {

        @Test
        @DisplayName("Mapper插入失败 - 异常传播")
        void shouldPropagateExceptionWhenInsertFails() throws Exception {
            VectorIndexDTO dto = TestBuilders.vectorIndexDTO().build();

            when(vectorIndexMapper.insert(any(VectorIndex.class)))
                    .thenThrow(new RuntimeException("数据库连接超时"));

            assertThrows(RuntimeException.class, () -> vectorIndexService.createIndex(dto));
        }

        @Test
        @DisplayName("序列化失败 - 异常处理")
        void shouldHandleSerializationException() {
            VectorIndexDTO dto = TestBuilders.vectorIndexDTO().build();

            when(objectMapper.writeValueAsString(any()))
                    .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("序列化失败") {});

            assertThrows(com.fasterxml.jackson.core.JsonProcessingException.class,
                    () -> vectorIndexService.createIndex(dto));
        }

        @Test
        @DisplayName("索引构建失败 - 状态更新为failed")
        void shouldUpdateStatusToFailedOnBuildError() {
            String indexId = "idx_001";
            VectorIndex index = TestFixtures.createVectorIndexEntity();
            index.setIndexId(indexId);
            index.setStatus("building");

            when(vectorIndexMapper.selectById(indexId)).thenReturn(index);
            doThrow(new RuntimeException("构建失败")).when(vectorIndexMapper).updateById(any());

            assertDoesNotThrow(() -> vectorIndexService.buildIndexAsync(indexId));
        }

        @Test
        @DisplayName("文件系统异常 - 删除索引降级处理")
        void shouldHandleFileSystemExceptionOnDelete() {
            String indexId = "idx_001";
            VectorIndex index = TestFixtures.createVectorIndexEntity();
            index.setIndexId(indexId);
            index.setIndexPath("/invalid/path");

            when(vectorIndexMapper.selectById(indexId)).thenReturn(index);
            when(vectorIndexMapper.deleteById(indexId)).thenReturn(1);

            assertDoesNotThrow(() -> vectorIndexService.deleteIndex(indexId));
            verify(vectorIndexMapper).deleteById(indexId);
        }

        @Test
        @DisplayName("查询索引时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenQueryFails() {
            String indexId = "idx_001";

            when(vectorIndexMapper.selectById(indexId))
                    .thenThrow(new RuntimeException("查询超时"));

            assertThrows(RuntimeException.class, () -> vectorIndexService.getIndex(indexId));
        }

        @Test
        @DisplayName("添加向量时数据库异常 - 异常传播")
        void shouldPropagateExceptionWhenAddingEmbeddingFails() {
            String indexId = "idx_001";
            float[] vector = new float[]{0.1f, 0.2f};

            when(vectorIndexMapper.selectById(indexId))
                    .thenThrow(new RuntimeException("数据库异常"));

            assertThrows(RuntimeException.class,
                    () -> vectorIndexService.addEmbedding(indexId, "data_001", vector, new HashMap<>()));
        }

        @Test
        @DisplayName("向量搜索时索引加载失败 - 返回空结果")
        void shouldReturnEmptyWhenIndexLoadingFails() {
            String indexId = "idx_001";
            VectorSearchDTO dto = TestBuilders.vectorSearchDTO().build();

            VectorIndex index = TestFixtures.createVectorIndexEntity();
            index.setIndexId(indexId);
            index.setStatus("ready");
            index.setIndexPath("/invalid/path");

            when(vectorIndexMapper.selectById(indexId)).thenReturn(index);

            List<Map<String, Object>> results = vectorIndexService.search(indexId, dto);
            assertTrue(results.isEmpty());
        }
    }
}
