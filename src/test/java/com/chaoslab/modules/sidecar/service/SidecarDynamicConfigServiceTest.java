package com.chaoslab.modules.sidecar.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.common.BaseTest;
import com.chaoslab.entity.*;
import com.chaoslab.mapper.*;
import com.chaoslab.modules.sidecar.dto.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.context.ApplicationEventPublisher;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("SidecarDynamicConfigService 单元测试")
class SidecarDynamicConfigServiceTest extends BaseTest {

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

    private final Map<String, DynamicConfig> configStore = new ConcurrentHashMap<>();
    private final Map<String, ConfigTemplate> templateStore = new ConcurrentHashMap<>();
    private final Map<String, ConfigChangeLog> logStore = new ConcurrentHashMap<>();
    private final Map<String, SidecarInstance> instanceStore = new ConcurrentHashMap<>();
    private final Map<String, SidecarConfig> sidecarConfigStore = new ConcurrentHashMap<>();

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        configStore.clear();
        templateStore.clear();
        logStore.clear();
        instanceStore.clear();
        sidecarConfigStore.clear();
        setupMockBehaviors();
    }

    private void setupMockBehaviors() {
        when(dynamicConfigMapper.insert(any(DynamicConfig.class))).thenAnswer(invocation -> {
            DynamicConfig config = invocation.getArgument(0);
            configStore.put(config.getConfigId(), config);
            return 1;
        });

        when(dynamicConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return configStore.values().stream().findFirst().orElse(null);
        });

        when(dynamicConfigMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return new ArrayList<>(configStore.values());
        });

        when(dynamicConfigMapper.selectCount(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return (long) configStore.size();
        });

        when(dynamicConfigMapper.updateById(any(DynamicConfig.class))).thenAnswer(invocation -> {
            DynamicConfig config = invocation.getArgument(0);
            configStore.put(config.getConfigId(), config);
            return 1;
        });

        when(configTemplateMapper.insert(any(ConfigTemplate.class))).thenAnswer(invocation -> {
            ConfigTemplate template = invocation.getArgument(0);
            templateStore.put(template.getTemplateId(), template);
            return 1;
        });

        when(configTemplateMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return templateStore.values().stream().findFirst().orElse(null);
        });

        when(configTemplateMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return new ArrayList<>(templateStore.values());
        });

        when(configChangeLogMapper.insert(any(ConfigChangeLog.class))).thenAnswer(invocation -> {
            ConfigChangeLog log = invocation.getArgument(0);
            logStore.put(log.getLogId(), log);
            return 1;
        });

        when(configChangeLogMapper.updateById(any(ConfigChangeLog.class))).thenAnswer(invocation -> {
            ConfigChangeLog log = invocation.getArgument(0);
            logStore.put(log.getLogId(), log);
            return 1;
        });

        when(sidecarInstanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return instanceStore.values().stream().findFirst().orElse(null);
        });

        when(sidecarInstanceMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return new ArrayList<>(instanceStore.values());
        });

        when(sidecarInstanceMapper.updateById(any(SidecarInstance.class))).thenAnswer(invocation -> {
            SidecarInstance instance = invocation.getArgument(0);
            instanceStore.put(instance.getInstanceId(), instance);
            return 1;
        });

        when(sidecarConfigMapper.insert(any(SidecarConfig.class))).thenAnswer(invocation -> {
            SidecarConfig config = invocation.getArgument(0);
            sidecarConfigStore.put(config.getConfigId(), config);
            return 1;
        });

        when(sidecarConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return sidecarConfigStore.values().stream().findFirst().orElse(null);
        });
    }

    // ==================== 动态配置管理测试 ====================

    @Nested
    @DisplayName("动态配置管理测试")
    class DynamicConfigTests {

        @Test
        @DisplayName("创建动态配置 - 成功")
        void createDynamicConfig_Success() {
            DynamicConfigCreateRequest request = new DynamicConfigCreateRequest();
            request.setConfigKey("sidecar.resource.cpu.limit");
            request.setConfigName("CPU限制");
            request.setConfigType("resource");
            request.setDescription("Sidecar CPU限制");
            request.setConfigValue(Map.of("value", "500m"));
            request.setHotReloadable(true);
            request.setScope("global");

            Mono<DynamicConfig> result = dynamicConfigService.createDynamicConfig(request);

            StepVerifier.create(result)
                    .expectNextMatches(config -> {
                        assertThat(config.getConfigId()).isNotNull().startsWith("dc-");
                        assertThat(config.getConfigKey()).isEqualTo("sidecar.resource.cpu.limit");
                        assertThat(config.getConfigValue()).containsEntry("value", "500m");
                        assertThat(config.getHotReloadable()).isTrue();
                        assertThat(config.getScope()).isEqualTo("global");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("创建动态配置 - 键已存在")
        void createDynamicConfig_DuplicateKey() {
            DynamicConfigCreateRequest request1 = new DynamicConfigCreateRequest();
            request1.setConfigKey("test.key");
            request1.setConfigName("Test");
            request1.setConfigValue(Map.of("value", "test"));
            dynamicConfigService.createDynamicConfig(request1).block();

            DynamicConfigCreateRequest request2 = new DynamicConfigCreateRequest();
            request2.setConfigKey("test.key");
            request2.setConfigName("Test2");
            request2.setConfigValue(Map.of("value", "test2"));

            Mono<DynamicConfig> result = dynamicConfigService.createDynamicConfig(request2);

            StepVerifier.create(result)
                    .expectErrorSatisfies(e -> {
                        assertThat(e).hasMessageContaining("配置键已存在");
                    })
                    .verify();
        }

        @Test
        @DisplayName("更新动态配置 - 成功")
        void updateDynamicConfig_Success() {
            DynamicConfigCreateRequest createRequest = new DynamicConfigCreateRequest();
            createRequest.setConfigKey("test.update.key");
            createRequest.setConfigName("Test");
            createRequest.setConfigValue(Map.of("value", "old"));
            createRequest.setHotReloadable(true);
            DynamicConfig created = dynamicConfigService.createDynamicConfig(createRequest).block();
            assertNotNull(created);

            DynamicConfigUpdateRequest updateRequest = new DynamicConfigUpdateRequest();
            updateRequest.setConfigId(created.getConfigId());
            updateRequest.setConfigValue(Map.of("value", "new"));
            updateRequest.setChangedBy("admin");
            updateRequest.setChangeReason("配置更新");

            when(dynamicConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(created);

            Mono<DynamicConfig> result = dynamicConfigService.updateDynamicConfig(updateRequest);

            StepVerifier.create(result)
                    .expectNextMatches(config -> {
                        assertThat(config.getConfigValue()).containsEntry("value", "new");
                        assertThat(config.getVersion()).isEqualTo(2);
                        assertThat(logStore).isNotEmpty();
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("获取动态配置 - 成功")
        void getDynamicConfig_Success() {
            DynamicConfigCreateRequest request = new DynamicConfigCreateRequest();
            request.setConfigKey("test.get.key");
            request.setConfigName("Test");
            request.setConfigValue(Map.of("value", "test"));
            DynamicConfig created = dynamicConfigService.createDynamicConfig(request).block();
            assertNotNull(created);

            when(dynamicConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(created);

            Mono<DynamicConfig> result = dynamicConfigService.getDynamicConfig("test.get.key");

            StepVerifier.create(result)
                    .expectNextMatches(config -> {
                        assertThat(config.getConfigKey()).isEqualTo("test.get.key");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("回滚配置 - 成功")
        void rollbackConfig_Success() {
            DynamicConfigCreateRequest createRequest = new DynamicConfigCreateRequest();
            createRequest.setConfigKey("test.rollback.key");
            createRequest.setConfigName("Test");
            createRequest.setConfigValue(Map.of("value", "original"));
            createRequest.setHotReloadable(true);
            DynamicConfig created = dynamicConfigService.createDynamicConfig(createRequest).block();
            assertNotNull(created);

            DynamicConfigUpdateRequest updateRequest = new DynamicConfigUpdateRequest();
            updateRequest.setConfigId(created.getConfigId());
            updateRequest.setConfigValue(Map.of("value", "updated"));
            updateRequest.setChangedBy("admin");
            when(dynamicConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(created);
            dynamicConfigService.updateDynamicConfig(updateRequest).block();

            ConfigChangeLog log = logStore.values().iterator().next();
            assertNotNull(log);

            when(configChangeLogMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(log);

            Mono<Void> result = dynamicConfigService.rollbackConfig(log.getLogId());

            StepVerifier.create(result)
                    .verifyComplete();

            assertThat(created.getConfigValue()).containsEntry("value", "original");
            assertThat(log.getRollbackStatus()).isEqualTo("ROLLED_BACK");
        }

        @Test
        @DisplayName("列出动态配置 - 按作用域过滤")
        void listDynamicConfigs_ByScope() {
            for (int i = 0; i < 5; i++) {
                DynamicConfigCreateRequest request = new DynamicConfigCreateRequest();
                request.setConfigKey("test.scope." + i);
                request.setConfigName("Test " + i);
                request.setConfigValue(Map.of("value", "test" + i));
                request.setScope(i < 3 ? "production" : "staging");
                dynamicConfigService.createDynamicConfig(request).block();
            }

            Mono<List<DynamicConfig>> result = dynamicConfigService.listDynamicConfigs("production", null);

            StepVerifier.create(result)
                    .expectNextMatches(configs -> {
                        assertThat(configs).hasSize(3);
                        return true;
                    })
                    .verifyComplete();
        }
    }

    // ==================== 配置模板管理测试 ====================

    @Nested
    @DisplayName("配置模板管理测试")
    class ConfigTemplateTests {

        @Test
        @DisplayName("创建配置模板 - 成功")
        void createConfigTemplate_Success() {
            ConfigTemplateCreateRequest request = new ConfigTemplateCreateRequest();
            request.setTemplateName("生产环境模板");
            request.setTemplateType("sidecar");
            request.setScenario("production");
            request.setDescription("生产环境高性能配置");
            request.setConfigData(Map.of("logLevel", "WARN", "timeout", 30));
            request.setResourceLimits(Map.of("cpuLimit", "1000m", "memoryLimit", "512Mi"));
            request.setPriority(1);

            Mono<ConfigTemplate> result = dynamicConfigService.createConfigTemplate(request);

            StepVerifier.create(result)
                    .expectNextMatches(template -> {
                        assertThat(template.getTemplateId()).isNotNull().startsWith("ct-");
                        assertThat(template.getTemplateName()).isEqualTo("生产环境模板");
                        assertThat(template.getScenario()).isEqualTo("production");
                        assertThat(template.getConfigData()).containsEntry("logLevel", "WARN");
                        assertThat(template.getResourceLimits()).containsEntry("cpuLimit", "1000m");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("按场景查询模板 - 成功")
        void getTemplatesByScenario_Success() {
            for (int i = 0; i < 3; i++) {
                ConfigTemplateCreateRequest request = new ConfigTemplateCreateRequest();
                request.setTemplateName("生产模板" + i);
                request.setTemplateType("sidecar");
                request.setScenario("production");
                request.setPriority(i);
                request.setConfigData(Map.of("index", i));
                dynamicConfigService.createConfigTemplate(request).block();
            }

            ConfigTemplateCreateRequest stagingRequest = new ConfigTemplateCreateRequest();
            stagingRequest.setTemplateName("预发模板");
            stagingRequest.setTemplateType("sidecar");
            stagingRequest.setScenario("staging");
            stagingRequest.setConfigData(Map.of());
            dynamicConfigService.createConfigTemplate(stagingRequest).block();

            Mono<List<ConfigTemplate>> result = dynamicConfigService.getTemplatesByScenario("production");

            StepVerifier.create(result)
                    .expectNextMatches(templates -> {
                        assertThat(templates).hasSize(3);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("应用模板到实例 - 成功")
        void applyTemplateToInstance_Success() {
            SidecarInstance instance = new SidecarInstance();
            instance.setInstanceId("si-test-001");
            instance.setNamespace("production");
            instance.setStatus("running");
            instanceStore.put(instance.getInstanceId(), instance);

            ConfigTemplateCreateRequest templateRequest = new ConfigTemplateCreateRequest();
            templateRequest.setTemplateName("测试模板");
            templateRequest.setTemplateType("sidecar");
            templateRequest.setScenario("production");
            templateRequest.setConfigData(Map.of("logLevel", "DEBUG"));
            templateRequest.setResourceLimits(Map.of("cpuLimit", "500m"));
            ConfigTemplate template = dynamicConfigService.createConfigTemplate(templateRequest).block();
            assertNotNull(template);

            when(sidecarInstanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(instance);
            when(configTemplateMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(template);

            ConfigApplyRequest applyRequest = new ConfigApplyRequest();
            applyRequest.setInstanceId(instance.getInstanceId());
            applyRequest.setTemplateId(template.getTemplateId());
            applyRequest.setAppliedBy("admin");
            applyRequest.setReason("配置更新");

            Mono<SidecarConfig> result = dynamicConfigService.applyTemplateToInstance(applyRequest);

            StepVerifier.create(result)
                    .expectNextMatches(config -> {
                        assertThat(config.getConfigData()).containsEntry("logLevel", "DEBUG");
                        assertThat(config.getVersion()).isEqualTo(1);
                        assertThat(instance.getStatus()).isEqualTo("config_pending");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("获取实例有效配置 - 成功")
        void getEffectiveConfig_Success() {
            SidecarInstance instance = new SidecarInstance();
            instance.setInstanceId("si-test-002");
            instance.setNamespace("production");
            instance.setStatus("running");
            instanceStore.put(instance.getInstanceId(), instance);

            DynamicConfigCreateRequest configRequest = new DynamicConfigCreateRequest();
            configRequest.setConfigKey("sidecar.resource.cpu.limit");
            configRequest.setConfigName("CPU限制");
            configRequest.setConfigValue(Map.of("value", "500m"));
            configRequest.setScope("production");
            dynamicConfigService.createDynamicConfig(configRequest).block();

            SidecarConfig sidecarConfig = new SidecarConfig();
            sidecarConfig.setConfigId("sc-test-001");
            sidecarConfig.setInstanceId(instance.getInstanceId());
            sidecarConfig.setConfigData(Map.of("customSetting", "enabled"));
            sidecarConfig.setApplied(true);
            sidecarConfigStore.put(sidecarConfig.getConfigId(), sidecarConfig);

            when(sidecarInstanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(instance);
            when(sidecarConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(sidecarConfig);

            Mono<Map<String, Object>> result = dynamicConfigService.getEffectiveConfig(instance.getInstanceId());

            StepVerifier.create(result)
                    .expectNextMatches(config -> {
                        assertThat(config).containsKey("sidecar.resource.cpu.limit");
                        assertThat(config).containsEntry("customSetting", "enabled");
                        return true;
                    })
                    .verifyComplete();
        }
    }

    // ==================== 缓存管理测试 ====================

    @Nested
    @DisplayName("缓存管理测试")
    class CacheTests {

        @Test
        @DisplayName("刷新配置缓存 - 成功")
        void refreshConfigCache_Success() {
            for (int i = 0; i < 5; i++) {
                DynamicConfigCreateRequest configRequest = new DynamicConfigCreateRequest();
                configRequest.setConfigKey("cache.test." + i);
                configRequest.setConfigName("Cache Test " + i);
                configRequest.setConfigValue(Map.of("value", "test" + i));
                dynamicConfigService.createDynamicConfig(configRequest).block();
            }

            for (int i = 0; i < 3; i++) {
                ConfigTemplateCreateRequest templateRequest = new ConfigTemplateCreateRequest();
                templateRequest.setTemplateName("Cache Template " + i);
                templateRequest.setTemplateType("sidecar");
                templateRequest.setScenario("scenario-" + i);
                templateRequest.setConfigData(Map.of());
                dynamicConfigService.createConfigTemplate(templateRequest).block();
            }

            Mono<Void> result = dynamicConfigService.refreshConfigCache();

            StepVerifier.create(result)
                    .verifyComplete();

            Mono<Map<String, Object>> statsResult = dynamicConfigService.getConfigStats();
            StepVerifier.create(statsResult)
                    .expectNextMatches(stats -> {
                        assertThat((Long) stats.get("cachedConfigs")).isEqualTo(5);
                        assertThat((Integer) stats.get("cachedTemplateScenarios")).isGreaterThan(0);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("获取配置统计 - 成功")
        void getConfigStats_Success() {
            for (int i = 0; i < 5; i++) {
                DynamicConfigCreateRequest request = new DynamicConfigCreateRequest();
                request.setConfigKey("stats.test." + i);
                request.setConfigName("Stats Test " + i);
                request.setConfigValue(Map.of("value", "test" + i));
                request.setHotReloadable(i % 2 == 0);
                dynamicConfigService.createDynamicConfig(request).block();
            }

            Mono<Map<String, Object>> result = dynamicConfigService.getConfigStats();

            StepVerifier.create(result)
                    .expectNextMatches(stats -> {
                        assertThat((Long) stats.get("totalConfigs")).isEqualTo(5);
                        assertThat((Long) stats.get("hotReloadableConfigs")).isEqualTo(3);
                        return true;
                    })
                    .verifyComplete();
        }
    }

    // ==================== 热更新测试 ====================

    @Nested
    @DisplayName("热更新测试")
    class HotReloadTests {

        @Test
        @DisplayName("热更新配置 - 触发事件")
        void updateDynamicConfig_HotReload() {
            DynamicConfigCreateRequest createRequest = new DynamicConfigCreateRequest();
            createRequest.setConfigKey("sidecar.hot.reload.test");
            createRequest.setConfigName("热更新测试");
            createRequest.setConfigValue(Map.of("value", "old"));
            createRequest.setHotReloadable(true);
            DynamicConfig created = dynamicConfigService.createDynamicConfig(createRequest).block();
            assertNotNull(created);

            DynamicConfigUpdateRequest updateRequest = new DynamicConfigUpdateRequest();
            updateRequest.setConfigId(created.getConfigId());
            updateRequest.setConfigValue(Map.of("value", "new"));
            updateRequest.setChangedBy("admin");
            when(dynamicConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(created);

            Mono<DynamicConfig> result = dynamicConfigService.updateDynamicConfig(updateRequest);

            StepVerifier.create(result)
                    .expectNextMatches(config -> {
                        verify(eventPublisher, atLeastOnce()).publishEvent(any());
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("非热更新配置 - 不触发事件")
        void updateDynamicConfig_NoHotReload() {
            DynamicConfigCreateRequest createRequest = new DynamicConfigCreateRequest();
            createRequest.setConfigKey("sidecar.no.hot.reload.test");
            createRequest.setConfigName("非热更新测试");
            createRequest.setConfigValue(Map.of("value", "old"));
            createRequest.setHotReloadable(false);
            DynamicConfig created = dynamicConfigService.createDynamicConfig(createRequest).block();
            assertNotNull(created);

            DynamicConfigUpdateRequest updateRequest = new DynamicConfigUpdateRequest();
            updateRequest.setConfigId(created.getConfigId());
            updateRequest.setConfigValue(Map.of("value", "new"));
            updateRequest.setChangedBy("admin");
            when(dynamicConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(created);

            Mono<DynamicConfig> result = dynamicConfigService.updateDynamicConfig(updateRequest);

            StepVerifier.create(result)
                    .expectNextMatches(config -> {
                        return true;
                    })
                    .verifyComplete();
        }
    }
}
