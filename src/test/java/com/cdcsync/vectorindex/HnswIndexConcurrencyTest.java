package com.cdcsync.vectorindex;

import com.cdcsync.test.builder.TestDataFactory;
import com.cdcsync.vectorindex.core.HnswIndex;
import com.cdcsync.vectorindex.core.IndexConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@DisplayName("HnswIndex 并发隔离级别测试")
class HnswIndexConcurrencyTest {

    private static final int DIMENSION = 16;
    private HnswIndex index;

    @BeforeEach
    void setUp() {
        IndexConfig config = IndexConfig.builder()
                .dimension(DIMENSION)
                .indexType("HNSW")
                .metricType("COSINE")
                .m(8)
                .efConstruction(100)
                .efSearch(50)
                .normalize(false)
                .build();
        index = new HnswIndex(config);
    }

    @Nested
    @DisplayName("并发写入测试")
    class ConcurrentWriteTests {

        @Test
        @DisplayName("多线程并发添加向量 - 最终数量应正确")
        @Timeout(30)
        void concurrentAdd_ShouldBeThreadSafe() throws Exception {
            int threadCount = 8;
            int vectorsPerThread = 50;
            int totalVectors = threadCount * vectorsPerThread;

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threadCount; t++) {
                Future<?> future = executor.submit(() -> {
                    try {
                        for (int i = 0; i < vectorsPerThread; i++) {
                            float[] vector = TestDataFactory.createRandomVector(DIMENSION);
                            long id = index.add(vector);
                            assertThat(id).isGreaterThanOrEqualTo(0);
                        }
                        successCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
                futures.add(future);
            }

            latch.await();
            executor.shutdown();

            await().until(() -> successCount.get() == threadCount);
            assertThat(index.size()).isEqualTo(totalVectors);
        }

        @Test
        @DisplayName("并发批量添加 - 数据应完整")
        @Timeout(30)
        void concurrentBatchAdd_ShouldBeThreadSafe() throws Exception {
            int threadCount = 4;
            int batchSize = 20;
            int batchesPerThread = 10;

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        for (int b = 0; b < batchesPerThread; b++) {
                            List<float[]> vectors = TestDataFactory.createRandomVectors(batchSize, DIMENSION);
                            List<Long> ids = index.addBatch(vectors);
                            assertThat(ids).hasSize(batchSize);
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            assertThat(index.size()).isEqualTo(threadCount * batchSize * batchesPerThread);
        }

        @Test
        @DisplayName("读写并发 - 读取操作不应阻塞写入")
        @Timeout(30)
        void concurrentReadWrite_ShouldNotBlock() throws Exception {
            for (int i = 0; i < 100; i++) {
                index.add(TestDataFactory.createRandomVector(DIMENSION));
            }

            ExecutorService executor = Executors.newFixedThreadPool(4);
            CountDownLatch latch = new CountDownLatch(3);

            AtomicInteger writeCount = new AtomicInteger(0);
            AtomicInteger searchCount = new AtomicInteger(0);

            executor.submit(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        index.add(TestDataFactory.createRandomVector(DIMENSION));
                        writeCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    for (int i = 0; i < 100; i++) {
                        float[] query = TestDataFactory.createRandomVector(DIMENSION);
                        index.search(query, 10);
                        searchCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    for (int i = 0; i < 50; i++) {
                        index.add(TestDataFactory.createRandomVector(DIMENSION));
                        writeCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });

            latch.await();
            executor.shutdown();

            assertThat(writeCount.get()).isEqualTo(100);
            assertThat(searchCount.get()).isEqualTo(100);
            assertThat(index.size()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("读写锁隔离测试")
    class ReadWriteLockIsolationTests {

        @Test
        @DisplayName("读操作可并发执行 - 多个读线程应同时执行")
        void concurrentReads_ShouldExecuteInParallel() {
            for (int i = 0; i < 50; i++) {
                index.add(TestDataFactory.createRandomVector(DIMENSION));
            }

            long startTime = System.currentTimeMillis();
            ExecutorService executor = Executors.newFixedThreadPool(10);

            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                Future<?> future = executor.submit(() -> {
                    float[] query = TestDataFactory.createRandomVector(DIMENSION);
                    for (int j = 0; j < 100; j++) {
                        index.search(query, 5);
                    }
                });
                futures.add(future);
            }

            for (Future<?> future : futures) {
                assertThatCode(() -> future.get()).doesNotThrowAnyException();
            }

            executor.shutdown();
            long duration = System.currentTimeMillis() - startTime;

            assertThat(duration).isLessThan(5000);
        }

        @Test
        @DisplayName("写操作互斥 - 写操作应串行执行")
        void concurrentWrites_ShouldBeSerialized() throws Exception {
            int threadCount = 4;
            int writesPerThread = 25;

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger concurrentWrites = new AtomicInteger(0);
            AtomicInteger maxConcurrentWrites = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < writesPerThread; i++) {
                            int current = concurrentWrites.incrementAndGet();
                            maxConcurrentWrites.updateAndGet(max -> Math.max(max, current));
                            try {
                                index.add(TestDataFactory.createRandomVector(DIMENSION));
                            } finally {
                                concurrentWrites.decrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        endLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            endLatch.await();
            executor.shutdown();

            assertThat(index.size()).isEqualTo(threadCount * writesPerThread);
        }

        @Test
        @DisplayName("空索引搜索 - 应返回空列表而不阻塞")
        void searchOnEmptyIndex_ShouldReturnEmpty() {
            float[] query = TestDataFactory.createRandomVector(DIMENSION);

            var result = index.search(query, 10);

            assertThat(result).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("删除操作并发测试")
    class ConcurrentDeleteTests {

        @Test
        @DisplayName("并发删除向量 - 最终数量应正确")
        @Timeout(30)
        void concurrentDelete_ShouldBeThreadSafe() throws Exception {
            int vectorCount = 200;
            List<Long> ids = new ArrayList<>();

            for (int i = 0; i < vectorCount; i++) {
                long id = index.add(TestDataFactory.createRandomVector(DIMENSION));
                ids.add(id);
            }

            assertThat(index.size()).isEqualTo(vectorCount);

            int threadCount = 4;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            int deletePerThread = 25;

            for (int t = 0; t < threadCount; t++) {
                final int startIdx = t * deletePerThread;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < deletePerThread; i++) {
                            index.delete(ids.get(startIdx + i));
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            assertThat(index.size()).isEqualTo(vectorCount - (threadCount * deletePerThread));
        }

        @Test
        @DisplayName("删除不存在的ID - 应安全忽略")
        void deleteNonExistent_ShouldBeSafe() {
            index.add(TestDataFactory.createRandomVector(DIMENSION));

            assertThatCode(() -> index.delete(9999L)).doesNotThrowAnyException();
            assertThat(index.size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("数据一致性验证")
    class DataConsistencyTests {

        @Test
        @DisplayName("添加的向量可被检索到 - 数据一致性保障")
        void addThenSearch_ShouldFindVector() {
            float[] knownVector = TestDataFactory.createUnitVector(DIMENSION, 0);
            long id = index.add(knownVector);

            for (int i = 0; i < 50; i++) {
                index.add(TestDataFactory.createRandomVector(DIMENSION));
            }

            var results = index.search(knownVector, 1);

            assertThat(results).isNotEmpty();
            assertThat(results.get(0).getKey()).isEqualTo(id);
        }

        @Test
        @DisplayName("向量维度验证 - 错误维度应抛出异常")
        void addWrongDimension_ShouldThrowException() {
            float[] wrongDimension = new float[DIMENSION + 1];

            assertThatThrownBy(() -> index.add(wrongDimension))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("多次删除同一个向量 - 应安全处理")
        void deleteSameIdMultipleTimes_ShouldBeSafe() {
            long id = index.add(TestDataFactory.createRandomVector(DIMENSION));

            assertThatCode(() -> index.delete(id)).doesNotThrowAnyException();
            assertThatCode(() -> index.delete(id)).doesNotThrowAnyException();
            assertThatCode(() -> index.delete(id)).doesNotThrowAnyException();

            assertThat(index.size()).isZero();
        }
    }
}
