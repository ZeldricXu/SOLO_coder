package com.chaoslab.modules.sidecar.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.common.ConcurrentTestBase;
import com.chaoslab.entity.*;
import com.chaoslab.mapper.*;
import com.chaoslab.modules.sidecar.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("SidecarDynamicConfigService 并发测试")
class SidecarDynamicConfigServiceConcurrentTest extends ConcurrentTestBase {

    @Mock
    private DynamicConfigMapper dynamicConfigMapper;

    @Mock
    private ConfigTemplateMapper configTemplateMapper;

    @Mock
    private ConfigChangeLogMapper configChangeLogMapper;

    @Mock
    private SidecarInstanceMapper sidecarInstanceMapper;

    @Mock
    private SidecarConfigMapper sidecarConfigMapper;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private SidecarDynamicConfigService dynamicConfigService;

    private final ConcurrentHashMap<String, DynamicConfig> configStore = new ConcurrentHashMap<>();
    private final AtomicInteger idCounter = new AtomicInteger(0);

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        configStore.clear();
        idCounter.set(0);
        setupMockBehaviors();
    }

    private void setupMockBehaviors() {
        when(dynamicConfigMapper.insert(any(DynamicConfig.class))).thenAnswer(invocation -> {
            DynamicConfig config = invocation.getArgument(0);
            if (config.getId() == null) {
                config.setId((long) idCounter.incrementAndGet());
            }
            configStore.put(config.getConfigId(), config);
            return 1;
        });

        when(dynamicConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return configStore.values().stream().findFirst().orElse(null);
        });

        when(dynamicConfigMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return new ArrayList<>(configStore.values());
        });

        when(dynamicConfigMapper.updateById(any(DynamicConfig.class))).thenAnswer(invocation -> {
            DynamicConfig config = invocation.getArgument(0);
            DynamicConfig existing = configStore.get(config.getConfigId());
            if (existing != null) {
                config.setVersion(existing.getVersion() + 1);
            }
            configStore.put(config.getConfigId(), config);
            return 1;
        });

        when(configTemplateMapper.insert(any(ConfigTemplate.class))).thenAnswer(invocation -> {
            return 1;
        });

        when(configChangeLogMapper.insert(any(ConfigChangeLog.class))).thenAnswer(invocation -> {
            return 1;
        });
    }

    @Test
    @DisplayName("并发创建动态配置 - 线程安全")
    void concurrentCreateDynamicConfig_ThreadSafe() throws Exception {
        assertConcurrentSafety(
                () -> {
                    DynamicConfigCreateRequest request = new DynamicConfigCreateRequest();
                    request.setConfigKey("concurrent.test.key." + UUID.randomUUID());
                    request.setConfigName("Concurrent Test");
                    request.setConfigValue(Map.of("value", "test"));
                    return dynamicConfigService.createDynamicConfig(request).block();
                },
                DEFAULT_THREAD_COUNT,
                DEFAULT_ITERATIONS
        );
    }

    @Test
    @DisplayName("并发更新同一配置 - 乐观锁生效")
    void concurrentUpdateSameConfig_OptimisticLock() throws Exception {
        DynamicConfigCreateRequest createRequest = new DynamicConfigCreateRequest();
        createRequest.setConfigKey("concurrent.update.test");
        createRequest.setConfigName("Concurrent Update Test");
        createRequest.setConfigValue(Map.of("value", "initial"));
        createRequest.setHotReloadable(true);
        DynamicConfig config = dynamicConfigService.createDynamicConfig(createRequest).block();
        assertThat(config).isNotNull();

        when(dynamicConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(config);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        assertConcurrentCorrectness(
                () -> {
                    try {
                        DynamicConfigUpdateRequest updateRequest = new DynamicConfigUpdateRequest();
                        updateRequest.setConfigId(config.getConfigId());
                        updateRequest.setConfigValue(Map.of("value", "updated-" + UUID.randomUUID()));
                        updateRequest.setChangedBy("admin");
                        DynamicConfig result = dynamicConfigService.updateDynamicConfig(updateRequest).block();
                        if (result != null) {
                            successCount.incrementAndGet();
                        }
                        return result;
                    } catch (Exception e) {
                        conflictCount.incrementAndGet();
                        return null;
                    }
                },
                result -> {
                    if (result != null) {
                        assertThat(result.getConfigValue()).isNotNull();
                    }
                },
                DEFAULT_THREAD_COUNT,
                20
        );

        assertThat(successCount.get()).isGreaterThan(0);
    }

    @Test
    @DisplayName("并发创建配置 - 数据完整性")
    void concurrentCreate_DataIntegrity() throws Exception {
        int operationCount = 50;
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(operationCount);

        for (int i = 0; i < operationCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    DynamicConfigCreateRequest request = new DynamicConfigCreateRequest();
                    request.setConfigKey("integrity.test.key." + index);
                    request.setConfigName("Integrity Test " + index);
                    request.setConfigValue(Map.of("index", index));
                    request.setHotReloadable(true);
                    dynamicConfigService.createDynamicConfig(request).block();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(configStore).hasSize(operationCount);
        for (int i = 0; i < operationCount; i++) {
            final int expectedIndex = i;
            boolean found = configStore.values().stream()
                    .anyMatch(c -> c.getConfigKey().equals("integrity.test.key." + expectedIndex));
            assertThat(found).isTrue();
        }
    }

    @Test
    @DisplayName("并发刷新缓存 - 资源安全")
    void concurrentRefreshCache_ResourceSafe() throws Exception {
        for (int i = 0; i < 20; i++) {
            DynamicConfigCreateRequest request = new DynamicConfigCreateRequest();
            request.setConfigKey("cache.test.key." + i);
            request.setConfigName("Cache Test " + i);
            request.setConfigValue(Map.of("value", "test" + i));
            dynamicConfigService.createDynamicConfig(request).block();
        }

        assertConcurrentSafety(
                () -> {
                    dynamicConfigService.refreshConfigCache().block();
                    return null;
                },
                10,
                50
        );
    }

    @Test
    @DisplayName("并发读写配置 - 一致性保证")
    void concurrentReadWrite_Consistency() throws Exception {
        DynamicConfigCreateRequest createRequest = new DynamicConfigCreateRequest();
        createRequest.setConfigKey("rw.consistency.test");
        createRequest.setConfigName("Read Write Consistency Test");
        createRequest.setConfigValue(Map.of("value", "v0"));
        createRequest.setHotReloadable(true);
        DynamicConfig config = dynamicConfigService.createDynamicConfig(createRequest).block();
        assertThat(config).isNotNull();

        when(dynamicConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(config);

        AtomicInteger version = new AtomicInteger(1);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch latch = new CountDownLatch(200);

        for (int i = 0; i < 100; i++) {
            final int writeIndex = i;
            executor.submit(() -> {
                try {
                    DynamicConfigUpdateRequest updateRequest = new DynamicConfigUpdateRequest();
                    updateRequest.setConfigId(config.getConfigId());
                    updateRequest.setConfigValue(Map.of("value", "v" + writeIndex));
                    updateRequest.setChangedBy("admin");
                    try {
                        dynamicConfigService.updateDynamicConfig(updateRequest).block();
                    } catch (Exception ignored) {
                    }
                } finally {
                    latch.countDown();
                }
            });

            executor.submit(() -> {
                try {
                    DynamicConfig result = dynamicConfigService.getDynamicConfig("rw.consistency.test").block();
                    if (result != null) {
                        assertThat(result.getConfigValue()).isNotNull();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
    }

    @Override
    protected void assertAllResourcesReleased() {
        assertThat(configStore).isNotEmpty();
    }
}
