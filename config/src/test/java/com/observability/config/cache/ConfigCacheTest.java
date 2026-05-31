package com.observability.config.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConfigCache 测试")
class ConfigCacheTest {

    private ConfigCache configCache;

    @BeforeEach
    void setup() {
        configCache = new ConfigCache();
    }

    @Nested
    @DisplayName("基础缓存操作测试")
    class BasicOperations {

        @Test
        @DisplayName("正常场景：存储和读取")
        void putAndGet_ConfigStored() {
            Map<String, Object> config = new HashMap<>();
            config.put("key", "value");

            configCache.put("test-ns", config);
            Optional<Map<String, Object>> result = configCache.get("test-ns");

            assertThat(result).isPresent();
            assertThat(result.get()).containsEntry("key", "value");
        }

        @Test
        @DisplayName("正常场景：命名空间不存在返回空")
        void get_NonexistentNamespace_ReturnsEmpty() {
            Optional<Map<String, Object>> result = configCache.get("nonexistent");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常场景：缓存失效")
        void invalidate_NamespaceRemoved() {
            Map<String, Object> config = new HashMap<>();
            configCache.put("test-ns", config);
            configCache.invalidate("test-ns");

            Optional<Map<String, Object>> result = configCache.get("test-ns");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常场景：清空所有缓存")
        void invalidateAll_AllCleared() {
            configCache.put("ns1", new HashMap<>());
            configCache.put("ns2", new HashMap<>());

            configCache.invalidateAll();

            assertThat(configCache.get("ns1")).isEmpty();
            assertThat(configCache.get("ns2")).isEmpty();
        }

        @Test
        @DisplayName("正常场景：获取缓存大小")
        void size_ReturnsCorrectCount() {
            configCache.put("ns1", new HashMap<>());
            configCache.put("ns2", new HashMap<>());
            configCache.put("ns3", new HashMap<>());

            assertThat(configCache.size()).isEqualTo(3);
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryTests {

        @Test
        @DisplayName("边界场景：空字符串命名空间")
        void putAndGet_EmptyNamespace_Success() {
            Map<String, Object> config = new HashMap<>();
            config.put("test", true);

            configCache.put("", config);
            Optional<Map<String, Object>> result = configCache.get("");

            assertThat(result).isPresent();
            assertThat(result.get()).containsEntry("test", true);
        }

        @Test
        @DisplayName("边界场景：超长命名空间")
        void putAndGet_LongNamespace_Success() {
            String longNs = "a".repeat(10000);
            Map<String, Object> config = new HashMap<>();
            config.put("data", "large");

            configCache.put(longNs, config);
            Optional<Map<String, Object>> result = configCache.get(longNs);

            assertThat(result).isPresent();
        }

        @Test
        @DisplayName("边界场景：特殊字符命名空间")
        void putAndGet_SpecialCharsNamespace_Success() {
            String ns = "ns-测试_123@#$%^&*()";
            Map<String, Object> config = new HashMap<>();
            config.put("valid", true);

            configCache.put(ns, config);
            Optional<Map<String, Object>> result = configCache.get(ns);

            assertThat(result).isPresent();
            assertThat(result.get()).containsEntry("valid", true);
        }

        @Test
        @DisplayName("边界场景：配置为null")
        void put_NullConfig_ThrowsExceptionOrHandles() {
            configCache.put("null-ns", null);
            assertThat(configCache.get("null-ns")).isEmpty();
        }

        @Test
        @DisplayName("边界场景：配置包含大量键")
        void put_LargeConfig_Success() {
            Map<String, Object> largeConfig = new HashMap<>();
            for (int i = 0; i < 10000; i++) {
                largeConfig.put("key" + i, "value" + i);
            }

            configCache.put("large-ns", largeConfig);
            Optional<Map<String, Object>> result = configCache.get("large-ns");

            assertThat(result).isPresent();
            assertThat(result.get()).hasSize(10000);
        }

        @Test
        @DisplayName("边界场景：配置包含null值")
        void put_ConfigWithNullValues_Success() {
            Map<String, Object> config = new HashMap<>();
            config.put("nullKey", null);
            config.put("normalKey", "normalValue");

            configCache.put("null-val-ns", config);
            Optional<Map<String, Object>> result = configCache.get("null-val-ns");

            assertThat(result).isPresent();
            assertThat(result.get()).containsEntry("nullKey", null);
            assertThat(result.get()).containsEntry("normalKey", "normalValue");
        }

        @Test
        @DisplayName("边界场景：多次put同一命名空间")
        void put_MultipleTimes_LatestValue() {
            Map<String, Object> config1 = new HashMap<>();
            config1.put("version", 1);
            configCache.put("multi-ns", config1);

            Map<String, Object> config2 = new HashMap<>();
            config2.put("version", 2);
            configCache.put("multi-ns", config2);

            Optional<Map<String, Object>> result = configCache.get("multi-ns");
            assertThat(result).isPresent();
            assertThat(result.get()).containsEntry("version", 2);
        }

        @Test
        @DisplayName("边界场景：失效不存在的命名空间不报错")
        void invalidate_NonexistentNamespace_NoError() {
            configCache.invalidate("nonexistent");
        }
    }

    @Nested
    @DisplayName("并发测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发场景：多线程同时put")
        void put_ConcurrentThreads_ThreadSafe() throws InterruptedException {
            int threadCount = 50;
            CountDownLatch latch = new CountDownLatch(threadCount);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                new Thread(() -> {
                    try {
                        Map<String, Object> config = new HashMap<>();
                        config.put("thread", idx);
                        configCache.put("ns-" + idx, config);
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(configCache.size()).isEqualTo(threadCount);
        }

        @Test
        @DisplayName("并发场景：多线程同时读写同一命名空间")
        void readWrite_ConcurrentOnSameNamespace_ThreadSafe() throws InterruptedException {
            int threadCount = 100;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            configCache.put("concurrent-ns", new HashMap<>());

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                new Thread(() -> {
                    try {
                        if (idx % 2 == 0) {
                            Map<String, Object> config = new HashMap<>();
                            config.put("update", idx);
                            configCache.put("concurrent-ns", config);
                        } else {
                            configCache.get("concurrent-ns");
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(errorCount.get()).isEqualTo(0);
        }

        @Test
        @DisplayName("并发场景：大量操作后的缓存一致性")
        void operations_MultipleConcurrent_ConsistentState() throws InterruptedException {
            int operations = 1000;
            CountDownLatch latch = new CountDownLatch(operations);

            for (int i = 0; i < operations; i++) {
                final int idx = i;
                new Thread(() -> {
                    try {
                        switch (idx % 3) {
                            case 0:
                                configCache.put("op-ns-" + idx, new HashMap<>());
                                break;
                            case 1:
                                configCache.get("op-ns-" + (idx - 1));
                                break;
                            case 2:
                                configCache.invalidate("op-ns-" + (idx - 2));
                                break;
                        }
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            boolean completed = latch.await(30, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
        }
    }

    @Nested
    @DisplayName("缓存过期测试")
    class ExpirationTests {

        @Test
        @DisplayName("正常场景：5分钟后过期（快速测试通过设置短过期时间）")
        void expire_AfterTTL_EntryRemoved() throws InterruptedException {
            ConfigCache shortCache = new ConfigCache(
                    Caffeine.newBuilder()
                            .expireAfterWrite(Duration.ofMillis(100))
                            .build()
            );

            Map<String, Object> config = new HashMap<>();
            config.put("key", "value");
            shortCache.put("expire-ns", config);

            assertThat(shortCache.get("expire-ns")).isPresent();

            Thread.sleep(200);

            assertThat(shortCache.get("expire-ns")).isEmpty();
        }
    }
}
