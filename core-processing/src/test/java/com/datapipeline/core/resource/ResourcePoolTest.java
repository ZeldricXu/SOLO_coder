package com.datapipeline.core.resource;

import com.datapipeline.common.test.TestUtils;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResourcePoolTest {

    private ResourcePool pool;
    private ResourcePool.ResourceFactory factory;

    @BeforeEach
    void setUp() {
        factory = () -> PooledResource.builder()
                .id("res_" + UUID.randomUUID().toString().substring(0, 8))
                .build();
    }

    @Nested
    @DisplayName("基本功能测试")
    class BasicFunctionalityTests {

        @Test
        @DisplayName("应创建资源池并正确初始化")
        void testPoolInitialization() {
            int maxSize = 5;
            pool = new ResourcePool(maxSize, factory);

            assertThat(pool.getTotalCreated()).isEqualTo(0);
            assertThat(pool.getAvailableCount()).isEqualTo(0);
            assertThat(pool.getAcquiredCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("获取资源应创建新资源")
        void testAcquireCreatesNewResource() throws Exception {
            pool = new ResourcePool(5, factory);

            PooledResource resource = pool.acquire(100, TimeUnit.MILLISECONDS);

            assertThat(resource).isNotNull();
            assertThat(resource.isAcquired()).isTrue();
            assertThat(pool.getTotalCreated()).isEqualTo(1);
            assertThat(pool.getAcquiredCount()).isEqualTo(1);
            assertThat(pool.getAvailableCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("释放资源应返回到可用池")
        void testReleaseReturnsToPool() throws Exception {
            pool = new ResourcePool(5, factory);

            PooledResource resource = pool.acquire(100, TimeUnit.MILLISECONDS);
            String resourceId = resource.getId();

            pool.release(resource);

            assertThat(pool.getAcquiredCount()).isEqualTo(0);
            assertThat(pool.getAvailableCount()).isEqualTo(1);
            assertThat(pool.getTotalCreated()).isEqualTo(1);

            PooledResource reacquired = pool.acquire(100, TimeUnit.MILLISECONDS);
            assertThat(reacquired.getId()).isEqualTo(resourceId);
        }

        @Test
        @DisplayName("不应创建超过最大数量的资源")
        void testMaxSizeLimit() throws Exception {
            int maxSize = 3;
            pool = new ResourcePool(maxSize, factory);

            PooledResource r1 = pool.acquire(100, TimeUnit.MILLISECONDS);
            PooledResource r2 = pool.acquire(100, TimeUnit.MILLISECONDS);
            PooledResource r3 = pool.acquire(100, TimeUnit.MILLISECONDS);

            assertThat(pool.getTotalCreated()).isEqualTo(maxSize);
            assertThat(pool.getAcquiredCount()).isEqualTo(maxSize);

            pool.release(r1);
            PooledResource r4 = pool.acquire(100, TimeUnit.MILLISECONDS);

            assertThat(pool.getTotalCreated()).isEqualTo(maxSize);
            assertThat(pool.getAcquiredCount()).isEqualTo(maxSize);
        }

        @Test
        @DisplayName("超时应抛出TimeoutException")
        void testAcquireTimeout() throws Exception {
            int maxSize = 1;
            pool = new ResourcePool(maxSize, factory);

            PooledResource resource = pool.acquire(100, TimeUnit.MILLISECONDS);

            long startTime = System.currentTimeMillis();
            Assertions.assertThrows(TimeoutException.class, () ->
                    pool.acquire(50, TimeUnit.MILLISECONDS)
            );
            long duration = System.currentTimeMillis() - startTime;

            assertThat(duration).isLessThan(200);
            pool.release(resource);
        }

        @Test
        @DisplayName("释放空资源应安全处理")
        void testReleaseNullResource() {
            pool = new ResourcePool(5, factory);

            Assertions.assertDoesNotThrow(() -> pool.release(null));

            assertThat(pool.getAvailableCount()).isEqualTo(0);
            assertThat(pool.getAcquiredCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("无效资源不应返回到池")
        void testInvalidResourceNotReturned() throws Exception {
            pool = new ResourcePool(5, factory);

            PooledResource resource = pool.acquire(100, TimeUnit.MILLISECONDS);
            resource.invalidate();

            pool.release(resource);

            assertThat(pool.getAvailableCount()).isEqualTo(0);
            assertThat(pool.getTotalCreated()).isEqualTo(1);

            PooledResource newResource = pool.acquire(100, TimeUnit.MILLISECONDS);
            assertThat(pool.getTotalCreated()).isEqualTo(2);
            pool.release(newResource);
        }

    }

    @Nested
    @DisplayName("并发安全测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发获取和释放应保持一致状态")
        void testConcurrentAcquireAndRelease() throws Exception {
            int maxSize = 10;
            int threadCount = 50;
            int iterationsPerThread = 100;

            pool = new ResourcePool(maxSize, factory);

            TestUtils.executeConcurrently(threadCount, iterationsPerThread, iteration -> {
                try {
                    PooledResource resource = pool.acquire(500, TimeUnit.MILLISECONDS);
                    TestUtils.sleepQuietly(1);
                    pool.release(resource);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                } catch (TimeoutException e) {
                    try {
                        TestUtils.sleepQuietly(50);
                    } catch (Exception ignored) {}
                }
            });

            assertThat(pool.getAcquiredCount()).as("所有资源应被释放").isEqualTo(0);
            assertThat(pool.getTotalCreated()).as("不应超过最大资源数").isLessThanOrEqualTo(maxSize);
        }

        @Test
        @DisplayName("并发获取不应创建超过最大数量的资源")
        void testConcurrentResourceCreationLimit() throws Exception {
            int maxSize = 5;
            int threadCount = 50;

            pool = new ResourcePool(maxSize, factory);

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            AtomicInteger timeoutCount = new AtomicInteger(0);
            AtomicInteger acquiredCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        try {
                            PooledResource resource = pool.acquire(200, TimeUnit.MILLISECONDS);
                            acquiredCount.incrementAndGet();
                            try {
                                TestUtils.sleepQuietly(50);
                            } finally {
                                pool.release(resource);
                            }
                        } catch (TimeoutException e) {
                            timeoutCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            doneLatch.await(30, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(pool.getTotalCreated()).as("不应超过最大资源数").isEqualTo(maxSize);
            assertThat(acquiredCount.get() + timeoutCount.get()).as("所有线程应完成").isEqualTo(threadCount);
            assertThat(pool.getAcquiredCount()).as("所有资源应被释放").isEqualTo(0);
        }

        @Test
        @DisplayName("资源使用计数应正确跟踪")
        void testResourceUseCountTracking() throws Exception {
            int maxSize = 2;
            pool = new ResourcePool(maxSize, factory);

            PooledResource r1 = pool.acquire(100, TimeUnit.MILLISECONDS);
            assertThat(r1.getUseCount().get()).isEqualTo(1);
            pool.release(r1);

            PooledResource r2 = pool.acquire(100, TimeUnit.MILLISECONDS);
            assertThat(r2.getId()).isEqualTo(r1.getId());
            assertThat(r2.getUseCount().get()).isEqualTo(2);
            pool.release(r2);
        }

    }

    @Nested
    @DisplayName("资源状态测试")
    class ResourceStateTests {

        @Test
        @DisplayName("资源状态应正确更新")
        void testResourceStateUpdates() throws Exception {
            pool = new ResourcePool(5, factory);

            PooledResource resource = pool.acquire(100, TimeUnit.MILLISECONDS);
            assertThat(resource.getState()).isEqualTo(PooledResource.State.ACQUIRED);

            pool.release(resource);
            assertThat(resource.getState()).isEqualTo(PooledResource.State.RELEASED);

            resource.invalidate();
            assertThat(resource.getState()).isEqualTo(PooledResource.State.INVALID);
            assertThat(resource.isValid()).isFalse();
        }

    }

}
