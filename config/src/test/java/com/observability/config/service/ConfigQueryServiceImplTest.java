package com.observability.config.service;

import com.observability.common.entity.ConfigEntity;
import com.observability.config.cache.ConfigCache;
import com.observability.config.loader.ConfigLoaderManager;
import com.observability.config.service.impl.ConfigQueryServiceImpl;
import com.observability.dal.repository.ConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConfigQueryService 测试")
class ConfigQueryServiceImplTest {

    @Mock
    private ConfigCache configCache;

    @Mock
    private ConfigLoaderManager configLoaderManager;

    @Mock
    private ConfigRepository configRepository;

    @InjectMocks
    private ConfigQueryServiceImpl configQueryService;

    @Nested
    @DisplayName("loadConfig 测试")
    class LoadConfigTests {

        @Test
        @DisplayName("正常场景：缓存命中")
        void loadConfig_CacheHit_ReturnsCached() {
            Map<String, Object> cachedConfig = new HashMap<>();
            cachedConfig.put("key1", "value1");
            cachedConfig.put("key2", 42);

            when(configCache.get("test-ns")).thenReturn(Optional.of(cachedConfig));

            Mono<Map<String, Object>> result = configQueryService.loadConfig("test-ns");

            StepVerifier.create(result)
                    .expectNextMatches(config ->
                            config.size() == 2 &&
                            "value1".equals(config.get("key1")) &&
                            (Integer) config.get("key2") == 42
                    )
                    .verifyComplete();

            verify(configLoaderManager, never()).loadFromAllSources(anyString());
        }

        @Test
        @DisplayName("正常场景：缓存未命中时从加载器加载")
        void loadConfig_CacheMiss_LoadsFromSources() {
            Map<String, Object> loadedConfig = new HashMap<>();
            loadedConfig.put("loaded", true);

            when(configCache.get("test-ns")).thenReturn(Optional.empty());
            when(configLoaderManager.loadFromAllSources("test-ns")).thenReturn(loadedConfig);

            Mono<Map<String, Object>> result = configQueryService.loadConfig("test-ns");

            StepVerifier.create(result)
                    .expectNextMatches(config ->
                            (Boolean) config.get("loaded"))
                    .verifyComplete();

            verify(configCache, times(1)).put(eq("test-ns"), any());
        }

        @Test
        @DisplayName("异常场景：缓存未命中且加载失败")
        void loadConfig_LoaderFails_ReturnsEmpty() {
            when(configCache.get("test-ns")).thenReturn(Optional.empty());
            when(configLoaderManager.loadFromAllSources("test-ns"))
                    .thenThrow(new RuntimeException("Loader failed"));

            Mono<Map<String, Object>> result = configQueryService.loadConfig("test-ns");

            StepVerifier.create(result)
                    .expectError(RuntimeException.class)
                    .verify();

            verify(configCache, never()).put(anyString(), any());
        }

        @Test
        @DisplayName("边界场景：命名空间为空字符串")
        void loadConfig_EmptyNamespace_LoadsEmpty() {
            Map<String, Object> emptyConfig = new HashMap<>();
            when(configCache.get("")).thenReturn(Optional.empty());
            when(configLoaderManager.loadFromAllSources("")).thenReturn(emptyConfig);

            StepVerifier.create(configQueryService.loadConfig(""))
                    .expectNext(emptyConfig)
                    .verifyComplete();
        }

        @Test
        @DisplayName("边界场景：超长命名空间")
        void loadConfig_LongNamespace_Success() {
            String longNs = "a".repeat(1000);
            Map<String, Object> config = new HashMap<>();
            when(configCache.get(longNs)).thenReturn(Optional.empty());
            when(configLoaderManager.loadFromAllSources(longNs)).thenReturn(config);

            StepVerifier.create(configQueryService.loadConfig(longNs))
                    .expectNext(config)
                    .verifyComplete();
        }

        @Test
        @DisplayName("边界场景：特殊字符命名空间")
        void loadConfig_SpecialCharsInNamespace_Success() {
            String ns = "ns-测试_123@#$";
            Map<String, Object> config = new HashMap<>();
            when(configCache.get(ns)).thenReturn(Optional.empty());
            when(configLoaderManager.loadFromAllSources(ns)).thenReturn(config);

            StepVerifier.create(configQueryService.loadConfig(ns))
                    .expectNext(config)
                    .verifyComplete();
        }

        @Test
        @DisplayName("并发场景：同一命名空间并发加载")
        void loadConfig_ConcurrentCalls_ThreadSafe() throws InterruptedException {
            Map<String, Object> config = new HashMap<>();
            config.put("key", "value");

            when(configCache.get("concurrent-ns")).thenReturn(Optional.empty());
            when(configLoaderManager.loadFromAllSources("concurrent-ns")).thenReturn(config);

            int threadCount = 10;
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
            java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    try {
                        configQueryService.loadConfig("concurrent-ns").block();
                        successCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            boolean completed = latch.await(10, java.util.concurrent.TimeUnit.SECONDS);
            assert completed;
            assert successCount.get() == threadCount;
        }
    }

    @Nested
    @DisplayName("getLatestConfig 测试")
    class GetLatestConfigTests {

        @Test
        @DisplayName("正常场景：配置存在")
        void getLatestConfig_ConfigExists_ReturnsConfig() {
            ConfigEntity config = new ConfigEntity();
            config.setConfigId("cfg-123");
            config.setNamespace("test-ns");
            config.setVersion(5);

            when(configRepository.findLatestByNamespace("test-ns")).thenReturn(Optional.of(config));

            StepVerifier.create(configQueryService.getLatestConfig("test-ns"))
                    .expectNextMatches(opt ->
                            opt.isPresent() &&
                                    "cfg-123".equals(opt.get().getConfigId()) &&
                                    opt.get().getVersion() == 5
                    )
                    .verifyComplete();
        }

        @Test
        @DisplayName("正常场景：配置不存在")
        void getLatestConfig_ConfigNotFound_ReturnsEmpty() {
            when(configRepository.findLatestByNamespace("nonexistent")).thenReturn(Optional.empty());

            StepVerifier.create(configQueryService.getLatestConfig("nonexistent"))
                    .expectNextMatches(Optional::isEmpty)
                    .verifyComplete();
        }

        @Test
        @DisplayName("异常场景：存储层抛出异常")
        void getLatestConfig_RepositoryThrowsException_Propagates() {
            when(configRepository.findLatestByNamespace(anyString()))
                    .thenThrow(new RuntimeException("DB error"));

            StepVerifier.create(configQueryService.getLatestConfig("test-ns"))
                    .expectError(RuntimeException.class)
                    .verify();
        }

        @Test
        @DisplayName("边界场景：命名空间为空")
        void getLatestConfig_EmptyNamespace_ReturnsEmpty() {
            when(configRepository.findLatestByNamespace("")).thenReturn(Optional.empty());

            StepVerifier.create(configQueryService.getLatestConfig(""))
                    .expectNextMatches(Optional::isEmpty)
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getConfigValue 测试")
    class GetConfigValueTests {

        @BeforeEach
        void setup() {
            Map<String, Object> config = new HashMap<>();
            config.put("timeout", 30);
            config.put("enabled", true);
            when(configCache.get("test-ns")).thenReturn(Optional.of(config));
        }

        @Test
        @DisplayName("正常场景：键存在")
        void getConfigValue_KeyExists_ReturnsValue() {
            StepVerifier.create(configQueryService.getConfigValue("test-ns", "timeout"))
                    .expectNextMatches(result ->
                            result.containsKey("timeout") &&
                                    ((Number) result.get("timeout")).intValue() == 30
                    )
                    .verifyComplete();
        }

        @Test
        @DisplayName("正常场景：键不存在")
        void getConfigValue_KeyNotFound_ReturnsEmpty() {
            StepVerifier.create(configQueryService.getConfigValue("test-ns", "nonexistent"))
                    .expectNextMatches(Map::isEmpty)
                    .verifyComplete();
        }

        @Test
        @DisplayName("边界场景：键为空字符串")
        void getConfigValue_EmptyKey_ReturnsEmpty() {
            StepVerifier.create(configQueryService.getConfigValue("test-ns", ""))
                    .expectNext(Map::isEmpty)
                    .verifyComplete();
        }

        @Test
        @DisplayName("边界场景：键为null")
        void getConfigValue_NullKey_ReturnsEmpty() {
            StepVerifier.create(configQueryService.getConfigValue("test-ns", null))
                    .expectNext(Map::isEmpty)
                    .verifyComplete();
        }

        @Test
        @DisplayName("边界场景：值为null")
        void getConfigValue_NullValue_ReturnsValue() {
            Map<String, Object> config = new HashMap<>();
            config.put("nullKey", null);
            when(configCache.get("test-ns")).thenReturn(Optional.of(config));

            StepVerifier.create(configQueryService.getConfigValue("test-ns", "nullKey"))
                    .expectNextMatches(result -> result.containsKey("nullKey"))
                    .verifyComplete();
        }

        @Test
        @DisplayName("异常场景：配置加载失败")
        void getConfigValue_LoadFails_PropagatesError() {
            when(configCache.get("error-ns")).thenReturn(Optional.empty());
            when(configLoaderManager.loadFromAllSources("error-ns"))
                    .thenThrow(new RuntimeException("Load failed"));

            StepVerifier.create(configQueryService.getConfigValue("error-ns", "key"))
                    .expectError(RuntimeException.class)
                    .verify();
        }
    }

    @Nested
    @DisplayName("refreshConfig 测试")
    class RefreshConfigTests {

        @Test
        @DisplayName("正常场景：刷新配置")
        void refreshConfig_InvalidatesCache() {
            Map<String, Object> newConfig = new HashMap<>();
            newConfig.put("refreshed", true);
            when(configLoaderManager.loadFromAllSources("test-ns")).thenReturn(newConfig);

            StepVerifier.create(configQueryService.refreshConfig("test-ns"))
                    .verifyComplete();

            verify(configCache, times(1)).invalidate("test-ns");
        }

        @Test
        @DisplayName("异常场景：刷新时加载失败")
        void refreshConfig_LoadFails_StillInvalidates() {
            when(configLoaderManager.loadFromAllSources("error-ns"))
                    .thenThrow(new RuntimeException("Load failed"));

            StepVerifier.create(configQueryService.refreshConfig("error-ns"))
                    .verifyComplete();

            verify(configCache, times(1)).invalidate("error-ns");
        }
    }
}
