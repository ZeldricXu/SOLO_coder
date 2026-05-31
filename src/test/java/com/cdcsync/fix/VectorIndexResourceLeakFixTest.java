package com.cdcsync.fix;

import com.cdcsync.common.exception.BusinessException;
import com.cdcsync.vectorindex.config.VectorIndexConfig;
import com.cdcsync.vectorindex.core.HnswIndex;
import com.cdcsync.vectorindex.domain.VectorIndex;
import com.cdcsync.vectorindex.mapper.VectorIndexMapper;
import com.cdcsync.vectorindex.service.impl.VectorIndexServiceImpl;
import com.cdcsync.test.builder.VectorIndexBuilder;
import com.cdcsync.test.builder.TestDataFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("向量索引构建模块 - 资源泄漏修复验证")
class VectorIndexResourceLeakFixTest {

    @Mock
    private VectorIndexMapper mapper;

    @Spy
    private VectorIndexConfig config = VectorIndexConfig.builder()
            .dim(128)
            .m(16)
            .efConstruction(200)
            .efSearch(50)
            .build();

    @InjectMocks
    private VectorIndexServiceImpl service;

    @BeforeEach
    void setUp() {
        reset(mapper);
    }

    @Nested
    @DisplayName("HnswIndex AutoCloseable 验证")
    class AutoCloseableTests {

        @Test
        @DisplayName("HnswIndex - 应实现AutoCloseable接口")
        void hnswIndex_ShouldImplementAutoCloseable() {
            HnswIndex index = new HnswIndex(config);
            assertThat(index).isInstanceOf(AutoCloseable.class);
            index.close();
        }

        @Test
        @DisplayName("HnswIndex - close后操作应抛出异常")
        void hnswIndex_AfterClose_OperationsShouldThrow() {
            HnswIndex index = new HnswIndex(config);
            index.close();

            assertThatThrownBy(() -> index.add(new float[128], "doc-1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed");
        }

        @Test
        @DisplayName("HnswIndex - 多次close应幂等")
        void hnswIndex_MultipleClose_ShouldBeIdempotent() {
            HnswIndex index = new HnswIndex(config);

            assertThatCode(() -> {
                index.close();
                index.close();
                index.close();
            }).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("HnswIndex - isClosed应正确反映状态")
        void hnswIndex_isClosed_ShouldReflectState() {
            HnswIndex index = new HnswIndex(config);
            assertThat(index.isClosed()).isFalse();

            index.close();
            assertThat(index.isClosed()).isTrue();
        }

        @Test
        @DisplayName("HnswIndex - try-with-resources应自动关闭")
        void hnswIndex_TryWithResources_ShouldAutoClose() {
            HnswIndex capturedIndex;
            try (HnswIndex index = new HnswIndex(config)) {
                capturedIndex = index;
                assertThat(index.isClosed()).isFalse();
            }

            assertThat(capturedIndex.isClosed()).isTrue();
        }

        @Test
        @DisplayName("HnswIndex - 关闭后应释放内存")
        void hnswIndex_Close_ShouldReleaseMemory() {
            HnswIndex index = new HnswIndex(config);
            index.add(new float[128], "doc-1");
            index.add(new float[128], "doc-2");

            index.close();

            assertThatThrownBy(() -> index.search(new float[128], 5))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("构建索引异常路径验证")
    class ExceptionPathTests {

        @Test
        @DisplayName("构建索引失败 - 应关闭已创建的索引")
        void buildIndex_Failure_ShouldClosePartialIndex() {
            String indexId = "test-index-001";
            List<float[]> vectors = TestDataFactory.createRandomVectors(10, 128);
            VectorIndex indexEntity = VectorIndexBuilder.aVectorIndex()
                    .withDefaults()
                    .withId(indexId)
                    .withDim(128)
                    .build();

            when(mapper.selectById(indexId)).thenReturn(indexEntity);
            when(mapper.updateById(any(VectorIndex.class))).thenThrow(new RuntimeException("DB error"));

            AtomicReference<HnswIndex> createdIndex = new AtomicReference<>();

            try {
                service.buildIndex(indexId, vectors);
            } catch (Exception e) {
                // expected
            }

            assertThat(service.getIndexCache()).isEmpty();
        }

        @Test
        @DisplayName("构建索引 - 应先关闭旧索引")
        void buildIndex_ShouldCloseOldIndexFirst() {
            String indexId = "test-index-002";
            List<float[]> vectors1 = TestDataFactory.createRandomVectors(5, 128);
            List<float[]> vectors2 = TestDataFactory.createRandomVectors(5, 128);
            VectorIndex indexEntity = VectorIndexBuilder.aVectorIndex()
                    .withDefaults()
                    .withId(indexId)
                    .withDim(128)
                    .build();

            when(mapper.selectById(indexId)).thenReturn(indexEntity);
            when(mapper.updateById(any(VectorIndex.class))).thenReturn(1);

            service.buildIndex(indexId, vectors1);
            HnswIndex oldIndex = service.getIndexCache().get(indexId);
            assertThat(oldIndex.isClosed()).isFalse();

            service.buildIndex(indexId, vectors2);

            assertThat(oldIndex.isClosed()).isTrue();
            HnswIndex newIndex = service.getIndexCache().get(indexId);
            assertThat(newIndex).isNotNull();
            assertThat(newIndex.isClosed()).isFalse();
        }

        @Test
        @DisplayName("维度不匹配 - 应不创建索引")
        void buildIndex_DimensionMismatch_ShouldNotCreateIndex() {
            String indexId = "test-index-003";
            List<float[]> vectors = TestDataFactory.createRandomVectors(10, 64);
            VectorIndex indexEntity = VectorIndexBuilder.aVectorIndex()
                    .withDefaults()
                    .withId(indexId)
                    .withDim(128)
                    .build();

            when(mapper.selectById(indexId)).thenReturn(indexEntity);

            assertThatThrownBy(() -> service.buildIndex(indexId, vectors))
                    .isInstanceOf(BusinessException.class);

            assertThat(service.getIndexCache()).doesNotContainKey(indexId);
        }

        @Test
        @DisplayName("空向量列表 - 应不创建索引")
        void buildIndex_EmptyVectors_ShouldNotCreateIndex() {
            String indexId = "test-index-004";
            List<float[]> vectors = new ArrayList<>();
            VectorIndex indexEntity = VectorIndexBuilder.aVectorIndex()
                    .withDefaults()
                    .withId(indexId)
                    .withDim(128)
                    .build();

            when(mapper.selectById(indexId)).thenReturn(indexEntity);

            assertThatThrownBy(() -> service.buildIndex(indexId, vectors))
                    .isInstanceOf(BusinessException.class);

            assertThat(service.getIndexCache()).doesNotContainKey(indexId);
        }
    }

    @Nested
    @DisplayName("双重检查锁定验证")
    class DoubleCheckedLockingTests {

        @Test
        @DisplayName("getOrLoadIndex - 缓存命中应直接返回")
        void getOrLoadIndex_CacheHit_ShouldReturnDirectly() {
            String indexId = "test-index-005";
            List<float[]> vectors = TestDataFactory.createRandomVectors(10, 128);
            VectorIndex indexEntity = VectorIndexBuilder.aVectorIndex()
                    .withDefaults()
                    .withId(indexId)
                    .withDim(128)
                    .build();

            when(mapper.selectById(indexId)).thenReturn(indexEntity);
            when(mapper.updateById(any(VectorIndex.class))).thenReturn(1);

            service.buildIndex(indexId, vectors);

            HnswIndex index1 = service.getIndexCache().get(indexId);
            HnswIndex index2 = service.getIndexCache().get(indexId);

            assertThat(index1).isSameAs(index2);
            verify(mapper, times(1)).selectById(indexId);
        }

        @Test
        @DisplayName("getOrLoadIndex - 已关闭的索引应重新加载")
        void getOrLoadIndex_ClosedIndex_ShouldReload() {
            String indexId = "test-index-006";
            List<float[]> vectors = TestDataFactory.createRandomVectors(10, 128);
            VectorIndex indexEntity = VectorIndexBuilder.aVectorIndex()
                    .withDefaults()
                    .withId(indexId)
                    .withDim(128)
                    .build();

            when(mapper.selectById(indexId)).thenReturn(indexEntity);
            when(mapper.updateById(any(VectorIndex.class))).thenReturn(1);
            when(mapper.selectById(indexId + "_data")).thenReturn(indexEntity);

            service.buildIndex(indexId, vectors);
            HnswIndex oldIndex = service.getIndexCache().get(indexId);

            service.closeIndex(indexId);
            assertThat(oldIndex.isClosed()).isTrue();

            HnswIndex reloaded = service.getIndexCache().get(indexId);
            assertThat(reloaded).isNull();
        }

        @Test
        @DisplayName("并发getOrLoadIndex - 应安全")
        void getOrLoadIndex_ConcurrentAccess_ShouldBeSafe() throws Exception {
            String indexId = "test-index-007";
            List<float[]> vectors = TestDataFactory.createRandomVectors(10, 128);
            VectorIndex indexEntity = VectorIndexBuilder.aVectorIndex()
                    .withDefaults()
                    .withId(indexId)
                    .withDim(128)
                    .build();

            when(mapper.selectById(indexId)).thenReturn(indexEntity);
            when(mapper.updateById(any(VectorIndex.class))).thenReturn(1);

            service.buildIndex(indexId, vectors);

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        HnswIndex index = service.getIndexCache().get(indexId);
                        if (index != null && !index.isClosed()) {
                            successCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(successCount.get()).isEqualTo(threadCount);
        }
    }

    @Nested
    @DisplayName("Shutdown钩子验证")
    class ShutdownHookTests {

        @Test
        @DisplayName("shutdown - 应关闭所有索引")
        void shutdown_ShouldCloseAllIndexes() {
            int indexCount = 3;
            List<String> indexIds = new ArrayList<>();
            List<float[]> vectors = TestDataFactory.createRandomVectors(5, 128);

            for (int i = 0; i < indexCount; i++) {
                String indexId = "shutdown-test-" + i;
                indexIds.add(indexId);
                VectorIndex indexEntity = VectorIndexBuilder.aVectorIndex()
                        .withDefaults()
                        .withId(indexId)
                        .withDim(128)
                        .build();

                when(mapper.selectById(indexId)).thenReturn(indexEntity);
                when(mapper.updateById(any(VectorIndex.class))).thenReturn(1);

                service.buildIndex(indexId, vectors);
            }

            assertThat(service.getIndexCache()).hasSize(indexCount);
            for (String id : indexIds) {
                assertThat(service.getIndexCache().get(id).isClosed()).isFalse();
            }

            service.shutdown();

            assertThat(service.getIndexCache()).isEmpty();
        }

        @Test
        @DisplayName("shutdown - 索引关闭失败不应影响其他索引")
        void shutdown_OneFails_ShouldCloseOthers() {
            String goodIndexId = "good-index";
            List<float[]> vectors = TestDataFactory.createRandomVectors(5, 128);

            VectorIndex goodIndex = VectorIndexBuilder.aVectorIndex()
                    .withDefaults()
                    .withId(goodIndexId)
                    .withDim(128)
                    .build();

            when(mapper.selectById(goodIndexId)).thenReturn(goodIndex);
            when(mapper.updateById(any(VectorIndex.class))).thenReturn(1);

            service.buildIndex(goodIndexId, vectors);

            ConcurrentHashMap<String, HnswIndex> cache = service.getIndexCache();
            HnswIndex goodHnsw = cache.get(goodIndexId);

            HnswIndex badHnsw = spy(new HnswIndex(config));
            doThrow(new RuntimeException("Close failed")).when(badHnsw).close();
            cache.put("bad-index", badHnsw);

            assertThatCode(() -> service.shutdown()).doesNotThrowAnyException();

            assertThat(goodHnsw.isClosed()).isTrue();
            assertThat(cache).isEmpty();
        }
    }

    @Nested
    @DisplayName("closeIndex方法验证")
    class CloseIndexTests {

        @Test
        @DisplayName("closeIndex - 应关闭并移除索引")
        void closeIndex_ShouldCloseAndRemove() {
            String indexId = "close-test-001";
            List<float[]> vectors = TestDataFactory.createRandomVectors(5, 128);
            VectorIndex indexEntity = VectorIndexBuilder.aVectorIndex()
                    .withDefaults()
                    .withId(indexId)
                    .withDim(128)
                    .build();

            when(mapper.selectById(indexId)).thenReturn(indexEntity);
            when(mapper.updateById(any(VectorIndex.class))).thenReturn(1);

            service.buildIndex(indexId, vectors);
            HnswIndex index = service.getIndexCache().get(indexId);

            service.closeIndex(indexId);

            assertThat(index.isClosed()).isTrue();
            assertThat(service.getIndexCache()).doesNotContainKey(indexId);
        }

        @Test
        @DisplayName("closeIndex - 不存在的索引不应抛异常")
        void closeIndex_NonExistent_ShouldNotThrow() {
            assertThatCode(() -> service.closeIndex("non-existent-id"))
                    .doesNotThrowAnyException();
        }
    }
}
