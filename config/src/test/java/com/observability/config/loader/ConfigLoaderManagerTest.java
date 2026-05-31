package com.observability.config.loader;

import com.observability.config.loader.impl.ConfigLoader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("ConfigLoaderManager 测试")
class ConfigLoaderManagerTest {

    private ConfigLoaderManager manager;
    private ConfigLoader databaseLoader;
    private ConfigLoader apiLoader;
    private ConfigLoader fileLoader;

    @BeforeEach
    void setup() {
        manager = new ConfigLoaderManager();
        databaseLoader = mock(ConfigLoader.class);
        apiLoader = mock(ConfigLoader.class);
        fileLoader = mock(ConfigLoader.class);

        when(databaseLoader.getName()).thenReturn("database");
        when(apiLoader.getName()).thenReturn("api");
        when(fileLoader.getName()).thenReturn("file");

        when(databaseLoader.getPriority()).thenReturn(100);
        when(apiLoader.getPriority()).thenReturn(200);
        when(fileLoader.getPriority()).thenReturn(300);
    }

    @Nested
    @DisplayName("加载器注册测试")
    class LoaderRegistrationTests {

        @Test
        @DisplayName("正常场景：注册单个加载器")
        void addLoader_SingleLoader_Success() {
            manager.addLoader(databaseLoader);
            assertThat(manager.getLoaders()).hasSize(1);
        }

        @Test
        @DisplayName("正常场景：注册多个加载器按优先级排序")
        void addLoader_MultipleLoaders_SortedByPriority() {
            manager.addLoader(fileLoader);
            manager.addLoader(databaseLoader);
            manager.addLoader(apiLoader);

            assertThat(manager.getLoaders())
                    .extracting(ConfigLoader::getPriority)
                    .containsExactly(100, 200, 300);
        }

        @Test
        @DisplayName("正常场景：注册null加载器")
        void addLoader_NullLoader_NoError() {
            manager.addLoader(null);
            assertThat(manager.getLoaders()).isEmpty();
        }
    }

    @Nested
    @DisplayName("加载配置测试")
    class LoadConfigTests {

        @Test
        @DisplayName("正常场景：所有加载器成功加载，高优先级覆盖低优先级")
        void loadFromAllSources_AllSuccess_HighPriorityWins() {
            manager.addLoader(databaseLoader);
            manager.addLoader(apiLoader);
            manager.addLoader(fileLoader);

            Map<String, Object> dbConfig = new HashMap<>();
            dbConfig.put("key1", "db-value");
            dbConfig.put("key2", "db-value");

            Map<String, Object> apiConfig = new HashMap<>();
            apiConfig.put("key1", "api-value");
            apiConfig.put("key3", "api-value");

            Map<String, Object> fileConfig = new HashMap<>();
            fileConfig.put("key1", "file-value");

            when(databaseLoader.load("test-ns")).thenReturn(dbConfig);
            when(apiLoader.load("test-ns")).thenReturn(apiConfig);
            when(fileLoader.load("test-ns")).thenReturn(fileConfig);

            Map<String, Object> result = manager.loadFromAllSources("test-ns");

            assertThat(result)
                    .containsEntry("key1", "file-value")
                    .containsEntry("key2", "db-value")
                    .containsEntry("key3", "api-value");
        }

        @Test
        @DisplayName("正常场景：无加载器时返回空Map")
        void loadFromAllSources_NoLoaders_ReturnsEmpty() {
            Map<String, Object> result = manager.loadFromAllSources("test-ns");
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("正常场景：部分加载器返回空配置")
        void loadFromAllSources_SomeEmpty_MergesCorrectly() {
            manager.addLoader(databaseLoader);
            manager.addLoader(apiLoader);

            Map<String, Object> dbConfig = new HashMap<>();
            dbConfig.put("key", "value");

            when(databaseLoader.load("test-ns")).thenReturn(dbConfig);
            when(apiLoader.load("test-ns")).thenReturn(new HashMap<>());

            Map<String, Object> result = manager.loadFromAllSources("test-ns");

            assertThat(result).containsEntry("key", "value");
        }

        @Test
        @DisplayName("异常场景：加载器抛出异常，继续使用其他加载器")
        void loadFromAllSources_LoaderFails_ContinuesWithOthers() {
            manager.addLoader(databaseLoader);
            manager.addLoader(apiLoader);
            manager.addLoader(fileLoader);

            Map<String, Object> dbConfig = new HashMap<>();
            dbConfig.put("key1", "db-value");

            Map<String, Object> fileConfig = new HashMap<>();
            fileConfig.put("key2", "file-value");

            when(databaseLoader.load("test-ns")).thenReturn(dbConfig);
            when(apiLoader.load("test-ns")).thenThrow(new RuntimeException("API failed"));
            when(fileLoader.load("test-ns")).thenReturn(fileConfig);

            Map<String, Object> result = manager.loadFromAllSources("test-ns");

            assertThat(result)
                    .containsEntry("key1", "db-value")
                    .containsEntry("key2", "file-value");
        }

        @Test
        @DisplayName("异常场景：所有加载器都失败")
        void loadFromAllSources_AllFail_ReturnsEmpty() {
            manager.addLoader(databaseLoader);
            manager.addLoader(apiLoader);

            when(databaseLoader.load("error-ns")).thenThrow(new RuntimeException("DB failed"));
            when(apiLoader.load("error-ns")).thenThrow(new RuntimeException("API failed"));

            Map<String, Object> result = manager.loadFromAllSources("error-ns");

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("边界场景：加载器返回null")
        void loadFromAllSources_LoaderReturnsNull_TreatedAsEmpty() {
            manager.addLoader(databaseLoader);
            manager.addLoader(apiLoader);

            Map<String, Object> apiConfig = new HashMap<>();
            apiConfig.put("key", "value");

            when(databaseLoader.load("test-ns")).thenReturn(null);
            when(apiLoader.load("test-ns")).thenReturn(apiConfig);

            Map<String, Object> result = manager.loadFromAllSources("test-ns");

            assertThat(result).containsEntry("key", "value");
        }

        @Test
        @DisplayName("边界场景：命名空间为空")
        void loadFromAllSources_EmptyNamespace_Success() {
            manager.addLoader(databaseLoader);
            Map<String, Object> config = new HashMap<>();
            config.put("key", "value");

            when(databaseLoader.load("")).thenReturn(config);

            Map<String, Object> result = manager.loadFromAllSources("");

            assertThat(result).containsEntry("key", "value");
        }

        @Test
        @DisplayName("边界场景：命名空间为特殊字符")
        void loadFromAllSources_SpecialCharsNamespace_Success() {
            manager.addLoader(databaseLoader);
            Map<String, Object> config = new HashMap<>();
            config.put("valid", true);

            String ns = "ns-测试_123@#$";
            when(databaseLoader.load(ns)).thenReturn(config);

            Map<String, Object> result = manager.loadFromAllSources(ns);

            assertThat(result).containsEntry("valid", true);
        }

        @Test
        @DisplayName("边界场景：配置包含嵌套结构")
        void loadFromAllSources_NestedConfig_DeepMerges() {
            manager.addLoader(databaseLoader);
            manager.addLoader(fileLoader);

            Map<String, Object> dbConfig = new HashMap<>();
            Map<String, Object> dbNested = new HashMap<>();
            dbNested.put("a", 1);
            dbNested.put("b", 2);
            dbConfig.put("nested", dbNested);

            Map<String, Object> fileConfig = new HashMap<>();
            Map<String, Object> fileNested = new HashMap<>();
            fileNested.put("b", 99);
            fileNested.put("c", 3);
            fileConfig.put("nested", fileNested);

            when(databaseLoader.load("test-ns")).thenReturn(dbConfig);
            when(fileLoader.load("test-ns")).thenReturn(fileConfig);

            Map<String, Object> result = manager.loadFromAllSources("test-ns");

            assertThat(result).containsKey("nested");
            Map<String, Object> resultNested = (Map<String, Object>) result.get("nested");
            assertThat(resultNested)
                    .containsEntry("a", 1)
                    .containsEntry("b", 99)
                    .containsEntry("c", 3);
        }
    }

    @Nested
    @DisplayName("并发场景测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发场景：多线程同时加载配置")
        void loadFromAllSources_ConcurrentCalls_ThreadSafe() throws InterruptedException {
            manager.addLoader(databaseLoader);
            manager.addLoader(fileLoader);

            Map<String, Object> config = new HashMap<>();
            config.put("concurrent", true);

            when(databaseLoader.load(any())).thenReturn(config);
            when(fileLoader.load(any())).thenReturn(new HashMap<>());

            int threadCount = 50;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                new Thread(() -> {
                    try {
                        Map<String, Object> result = manager.loadFromAllSources("concurrent-ns-" + idx);
                        if (result != null && result.containsKey("concurrent")) {
                            successCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(successCount.get()).isEqualTo(threadCount);
        }

        @Test
        @DisplayName("并发场景：加载时添加加载器")
        void loadAndAddLoader_Concurrent_ThreadSafe() throws InterruptedException {
            manager.addLoader(databaseLoader);

            Map<String, Object> config = new HashMap<>();
            when(databaseLoader.load(any())).thenReturn(config);

            int threadCount = 50;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                new Thread(() -> {
                    try {
                        if (idx % 2 == 0) {
                            manager.loadFromAllSources("test-ns");
                        } else {
                            ConfigLoader newLoader = mock(ConfigLoader.class);
                            when(newLoader.getName()).thenReturn("loader-" + idx);
                            when(newLoader.getPriority()).thenReturn(1000 + idx);
                            when(newLoader.load(any())).thenReturn(new HashMap<>());
                            manager.addLoader(newLoader);
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
    }
}
