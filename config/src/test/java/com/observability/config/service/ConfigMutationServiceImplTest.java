package com.observability.config.service;

import com.observability.common.entity.ConfigEntity;
import com.observability.config.cache.ConfigCache;
import com.observability.config.listener.ConfigChangeListenerManager;
import com.observability.config.service.impl.ConfigMutationServiceImpl;
import com.observability.dal.repository.ConfigRepository;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConfigMutationService 测试")
class ConfigMutationServiceImplTest {

    @Mock
    private ConfigRepository configRepository;

    @Mock
    private ConfigCache configCache;

    @Mock
    private ConfigChangeListenerManager listenerManager;

    @InjectMocks
    private ConfigMutationServiceImpl configMutationService;

    @Nested
    @DisplayName("saveConfig 测试")
    class SaveConfigTests {

        @Test
        @DisplayName("正常场景：创建新配置（无现有版本）")
        void saveConfig_NoExistingConfig_Version1() {
            Map<String, Object> params = new HashMap<>();
            params.put("timeout", 30);
            params.put("retries", 3);

            when(configRepository.findLatestByNamespace("new-ns")).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Mono<ConfigEntity> result = configMutationService.saveConfig("new-ns", params, "database");

            StepVerifier.create(result)
                    .expectNextMatches(config ->
                            config.getVersion() == 1 &&
                                    "database".equals(config.getSource()) &&
                                    config.getEnabled()
                    )
                    .verifyComplete();

            verify(configCache, times(1)).invalidate("new-ns");
            verify(listenerManager, times(1)).notifyListeners(eq("new-ns"), any());
        }

        @Test
        @DisplayName("正常场景：更新配置（有现有版本）")
        void saveConfig_ExistingConfig_IncrementVersion() {
            ConfigEntity existing = new ConfigEntity();
            existing.setVersion(3);

            Map<String, Object> params = new HashMap<>();
            params.put("timeout", 60);

            when(configRepository.findLatestByNamespace("existing-ns")).thenReturn(Optional.of(existing));
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Mono<ConfigEntity> result = configMutationService.saveConfig("existing-ns", params, null);

            StepVerifier.create(result)
                    .expectNextMatches(config -> config.getVersion() == 4)
                    .verifyComplete();
        }

        @Test
        @DisplayName("边界场景：source为null时使用默认值")
        void saveConfig_NullSource_UsesDefault() {
            Map<String, Object> params = new HashMap<>();

            when(configRepository.findLatestByNamespace("test-ns")).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Mono<ConfigEntity> result = configMutationService.saveConfig("test-ns", params, null);

            StepVerifier.create(result)
                    .expectNextMatches(config -> "database".equals(config.getSource()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("边界场景：参数为空Map")
        void saveConfig_EmptyParams_Success() {
            Map<String, Object> params = new HashMap<>();

            when(configRepository.findLatestByNamespace("empty-ns")).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Mono<ConfigEntity> result = configMutationService.saveConfig("empty-ns", params, "manual");

            StepVerifier.create(result)
                    .expectNextMatches(config -> config.getVersion() == 1)
                    .verifyComplete();
        }

        @Test
        @DisplayName("边界场景：参数为null")
        void saveConfig_NullParams_Success() {
            when(configRepository.findLatestByNamespace("null-ns")).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Mono<ConfigEntity> result = configMutationService.saveConfig("null-ns", null, "manual");

            StepVerifier.create(result)
                    .expectNextMatches(config -> config.getVersion() == 1)
                    .verifyComplete();
        }

        @Test
        @DisplayName("边界场景：大量参数")
        void saveConfig_LargeParams_Success() {
            Map<String, Object> params = new HashMap<>();
            for (int i = 0; i < 1000; i++) {
                params.put("key" + i, "value" + i);
            }

            when(configRepository.findLatestByNamespace("large-ns")).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Mono<ConfigEntity> result = configMutationService.saveConfig("large-ns", params, "api");

            StepVerifier.create(result)
                    .expectNextMatches(config -> config.getVersion() == 1)
                    .verifyComplete();
        }

        @Test
        @DisplayName("异常场景：保存失败")
        void saveConfig_RepositoryFails_ThrowsException() {
            Map<String, Object> params = new HashMap<>();

            when(configRepository.findLatestByNamespace("error-ns")).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenThrow(new RuntimeException("DB error"));

            Mono<ConfigEntity> result = configMutationService.saveConfig("error-ns", params, "api");

            StepVerifier.create(result)
                    .expectErrorMatches(e -> e.getMessage().equals("DB error"))
                    .verify();

            verify(configCache, never()).invalidate(any());
            verify(listenerManager, never()).notifyListeners(any(), any());
        }

        @Test
        @DisplayName("异常场景：查询最新版本失败")
        void saveConfig_FindLatestFails_ThrowsException() {
            Map<String, Object> params = new HashMap<>();

            when(configRepository.findLatestByNamespace("error-ns"))
                    .thenThrow(new RuntimeException("DB connection failed"));

            Mono<ConfigEntity> result = configMutationService.saveConfig("error-ns", params, "api");

            StepVerifier.create(result)
                    .expectErrorMatches(e -> e.getMessage().equals("DB connection failed"))
                    .verify();
        }

        @Test
        @DisplayName("边界场景：超长命名空间")
        void saveConfig_LongNamespace_Success() {
            String longNs = "a".repeat(500);
            Map<String, Object> params = new HashMap<>();

            when(configRepository.findLatestByNamespace(longNs)).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Mono<ConfigEntity> result = configMutationService.saveConfig(longNs, params, "api");

            StepVerifier.create(result)
                    .expectNextMatches(config -> config.getVersion() == 1)
                    .verifyComplete();
        }

        @Test
        @DisplayName("并发场景：并发保存同一命名空间")
        void saveConfig_ConcurrentSaves_ThreadSafe() throws InterruptedException {
            int threadCount = 10;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger versionCounter = new AtomicInteger(0);

            when(configRepository.findLatestByNamespace("concurrent-ns"))
                    .thenAnswer(inv -> {
                        int v = versionCounter.get();
                        if (v == 0) return Optional.empty();
                        ConfigEntity c = new ConfigEntity();
                        c.setVersion(v);
                        return Optional.of(c);
                    });
            when(configRepository.save(any()))
                    .thenAnswer(inv -> {
                        versionCounter.incrementAndGet();
                        return inv.getArgument(0);
                    });

            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    try {
                        configMutationService.saveConfig("concurrent-ns", new HashMap<>(), "api").block();
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            assert completed;
            assert versionCounter.get() == threadCount;
        }
    }

    @Nested
    @DisplayName("监听器管理测试")
    class ListenerTests {

        @Test
        @DisplayName("正常场景：添加监听器")
        void addListener_Success() {
            Consumer<Map<String, Object>> listener = config -> {};

            configMutationService.addListener("test-ns", listener);

            verify(listenerManager, times(1)).addListener("test-ns", listener);
        }

        @Test
        @DisplayName("正常场景：移除监听器")
        void removeListener_Success() {
            Consumer<Map<String, Object>> listener = config -> {};

            configMutationService.removeListener("test-ns", listener);

            verify(listenerManager, times(1)).removeListener("test-ns", listener);
        }

        @Test
        @DisplayName("正常场景：监听器被正确通知")
        void saveConfig_NotifiesListeners() {
            Map<String, Object> params = new HashMap<>();
            params.put("key", "value");

            AtomicReference<Map<String, Object>> receivedConfig = new AtomicReference<>();
            doAnswer(inv -> {
                receivedConfig.set(inv.getArgument(1));
                return null;
            }).when(listenerManager).notifyListeners(eq("test-ns"), any());

            when(configRepository.findLatestByNamespace("test-ns")).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            configMutationService.saveConfig("test-ns", params, "api").block();

            assert receivedConfig.get() != null;
            assert "value".equals(receivedConfig.get().get("key"));
        }

        @Test
        @DisplayName("异常场景：监听器抛出异常不影响主流程")
        void saveConfig_ListenerThrowsException_StillSaves() {
            Map<String, Object> params = new HashMap<>();

            doThrow(new RuntimeException("Listener error"))
                    .when(listenerManager).notifyListeners(eq("test-ns"), any());

            when(configRepository.findLatestByNamespace("test-ns")).thenReturn(Optional.empty());
            when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Mono<ConfigEntity> result = configMutationService.saveConfig("test-ns", params, "api");

            StepVerifier.create(result)
                    .expectNextMatches(config -> config.getVersion() == 1)
                    .verifyComplete();
        }
    }
}
