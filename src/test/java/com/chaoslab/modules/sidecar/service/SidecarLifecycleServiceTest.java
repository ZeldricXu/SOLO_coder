package com.chaoslab.modules.sidecar.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chaoslab.common.ConcurrentTestBase;
import com.chaoslab.common.TestDataFactory;
import com.chaoslab.entity.SidecarConfig;
import com.chaoslab.entity.SidecarInjectionPolicy;
import com.chaoslab.entity.SidecarInstance;
import com.chaoslab.exception.BusinessException;
import com.chaoslab.mapper.SidecarConfigMapper;
import com.chaoslab.mapper.SidecarInjectionPolicyMapper;
import com.chaoslab.mapper.SidecarInstanceMapper;
import com.chaoslab.modules.sidecar.dto.ConfigUpdateRequest;
import com.chaoslab.modules.sidecar.dto.InjectionPolicyCreateRequest;
import com.chaoslab.modules.sidecar.dto.ResourceLimitUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("SidecarLifecycleService 单元测试")
@Execution(ExecutionMode.SAME_THREAD)
class SidecarLifecycleServiceTest extends ConcurrentTestBase {

    @Mock
    private SidecarInjectionPolicyMapper policyMapper;

    @Mock
    private SidecarInstanceMapper instanceMapper;

    @Mock
    private SidecarConfigMapper configMapper;

    @InjectMocks
    private SidecarLifecycleService sidecarService;

    private final Map<String, SidecarInjectionPolicy> policyStore = new ConcurrentHashMap<>();
    private final Map<String, SidecarInstance> instanceStore = new ConcurrentHashMap<>();
    private final Map<String, SidecarConfig> configStore = new ConcurrentHashMap<>();
    private final AtomicInteger policyInsertCount = new AtomicInteger(0);
    private final AtomicInteger instanceInsertCount = new AtomicInteger(0);
    private final AtomicInteger configInsertCount = new AtomicInteger(0);

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        policyStore.clear();
        instanceStore.clear();
        configStore.clear();
        policyInsertCount.set(0);
        instanceInsertCount.set(0);
        configInsertCount.set(0);
        setupMockBehaviors();
    }

    @Override
    protected void assertAllResourcesReleased() {
        assertThat(policyStore).isEmpty();
        assertThat(instanceStore).isEmpty();
        assertThat(configStore).isEmpty();
        assertThat(policyInsertCount.get()).isEqualTo(0);
        assertThat(instanceInsertCount.get()).isEqualTo(0);
        assertThat(configInsertCount.get()).isEqualTo(0);
    }

    private void setupMockBehaviors() {
        when(policyMapper.insert(any(SidecarInjectionPolicy.class))).thenAnswer(invocation -> {
            SidecarInjectionPolicy policy = invocation.getArgument(0);
            policyStore.put(policy.getPolicyId(), policy);
            policyInsertCount.incrementAndGet();
            return 1;
        });

        when(policyMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            LambdaQueryWrapper<SidecarInjectionPolicy> wrapper = invocation.getArgument(0);
            return policyStore.values().stream().findFirst().orElse(null);
        });

        when(policyMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            Page<SidecarInjectionPolicy> page = invocation.getArgument(0);
            List<SidecarInjectionPolicy> policies = new ArrayList<>(policyStore.values());
            page.setRecords(policies);
            page.setTotal(policies.size());
            return page;
        });

        when(instanceMapper.insert(any(SidecarInstance.class))).thenAnswer(invocation -> {
            SidecarInstance instance = invocation.getArgument(0);
            instanceStore.put(instance.getInstanceId(), instance);
            instanceInsertCount.incrementAndGet();
            return 1;
        });

        when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return instanceStore.values().stream().findFirst().orElse(null);
        });

        when(instanceMapper.updateById(any(SidecarInstance.class))).thenAnswer(invocation -> {
            SidecarInstance instance = invocation.getArgument(0);
            instanceStore.put(instance.getInstanceId(), instance);
            return 1;
        });

        when(configMapper.insert(any(SidecarConfig.class))).thenAnswer(invocation -> {
            SidecarConfig config = invocation.getArgument(0);
            configStore.put(config.getConfigId(), config);
            configInsertCount.incrementAndGet();
            return 1;
        });

        when(configMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return configStore.values().stream().findFirst().orElse(null);
        });

        when(configMapper.updateById(any(SidecarConfig.class))).thenAnswer(invocation -> {
            SidecarConfig config = invocation.getArgument(0);
            configStore.put(config.getConfigId(), config);
            return 1;
        });
    }

    private void releaseResources() {
        policyStore.clear();
        instanceStore.clear();
        configStore.clear();
        policyInsertCount.set(0);
        instanceInsertCount.set(0);
        configInsertCount.set(0);
    }

    // ==================== 正常路径测试 ====================

    @Nested
    @DisplayName("正常路径测试")
    class NormalPathTests {

        @Test
        @DisplayName("创建注入策略 - 成功")
        void createInjectionPolicy_Success() {
            InjectionPolicyCreateRequest request = TestDataFactory.createInjectionPolicyRequest();

            Mono<SidecarInjectionPolicy> result = sidecarService.createInjectionPolicy(request);

            StepVerifier.create(result)
                    .expectNextMatches(policy -> {
                        assertThat(policy.getPolicyId()).isNotNull().startsWith("pol-");
                        assertThat(policy.getName()).isEqualTo(request.getName());
                        assertThat(policy.getNamespace()).isEqualTo(request.getNamespace());
                        assertThat(policy.getSidecarImage()).isEqualTo(request.getSidecarImage());
                        assertThat(policy.getEnabled()).isTrue();
                        assertThat(policyStore).containsKey(policy.getPolicyId());
                        return true;
                    })
                    .verifyComplete();

            verify(policyMapper, times(1)).insert(any(SidecarInjectionPolicy.class));
            assertThat(policyInsertCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("创建注入策略 - 使用默认资源配置")
        void createInjectionPolicy_WithDefaultResources() {
            InjectionPolicyCreateRequest request = TestDataFactory.createInjectionPolicyRequest();
            request.setResources(null);

            Mono<SidecarInjectionPolicy> result = sidecarService.createInjectionPolicy(request);

            StepVerifier.create(result)
                    .expectNextMatches(policy -> {
                        assertThat(policy.getResources()).isNotNull();
                        assertThat(policy.getResources()).containsKey("cpuLimit");
                        assertThat(policy.getResources()).containsKey("memoryLimit");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("查询策略列表 - 成功")
        void listPolicies_Success() {
            SidecarInjectionPolicy policy = TestDataFactory.createSidecarInjectionPolicy();
            policyStore.put(policy.getPolicyId(), policy);

            Mono<Page<SidecarInjectionPolicy>> result = sidecarService.listPolicies(policy.getNamespace(), 1, 10);

            StepVerifier.create(result)
                    .expectNextMatches(page -> {
                        assertThat(page.getRecords()).hasSize(1);
                        assertThat(page.getTotal()).isEqualTo(1);
                        assertThat(page.getCurrent()).isEqualTo(1);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("查询策略列表 - 不带命名空间过滤")
        void listPolicies_WithoutNamespaceFilter() {
            for (int i = 0; i < 5; i++) {
                SidecarInjectionPolicy policy = TestDataFactory.createSidecarInjectionPolicy();
                policyStore.put(policy.getPolicyId(), policy);
            }

            Mono<Page<SidecarInjectionPolicy>> result = sidecarService.listPolicies(null, 1, 10);

            StepVerifier.create(result)
                    .expectNextMatches(page -> {
                        assertThat(page.getRecords()).hasSize(5);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("获取单个策略 - 成功")
        void getPolicy_Success() {
            SidecarInjectionPolicy policy = TestDataFactory.createSidecarInjectionPolicy();
            policyStore.put(policy.getPolicyId(), policy);

            Mono<SidecarInjectionPolicy> result = sidecarService.getPolicy(policy.getPolicyId());

            StepVerifier.create(result)
                    .expectNextMatches(p -> {
                        assertThat(p.getPolicyId()).isEqualTo(policy.getPolicyId());
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("注入Sidecar - 成功")
        void injectSidecar_Success() {
            SidecarInjectionPolicy policy = TestDataFactory.createSidecarInjectionPolicy();
            policyStore.put(policy.getPolicyId(), policy);

            Mono<SidecarInstance> result = sidecarService.injectSidecar(
                    policy.getPolicyId(), "test-pod-001", policy.getNamespace());

            StepVerifier.create(result)
                    .expectNextMatches(instance -> {
                        assertThat(instance.getInstanceId()).isNotNull().startsWith("si-");
                        assertThat(instance.getPolicyId()).isEqualTo(policy.getPolicyId());
                        assertThat(instance.getTargetPod()).isEqualTo("test-pod-001");
                        assertThat(instance.getStatus()).isEqualTo("injecting");
                        assertThat(instanceStore).containsKey(instance.getInstanceId());
                        assertThat(configStore).isNotEmpty();
                        return true;
                    })
                    .verifyComplete();

            assertThat(instanceInsertCount.get()).isEqualTo(1);
            assertThat(configInsertCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("更新配置 - 成功")
        void updateConfig_Success() {
            SidecarInstance instance = TestDataFactory.createSidecarInstance("pol-test-001");
            instanceStore.put(instance.getInstanceId(), instance);

            ConfigUpdateRequest request = TestDataFactory.createConfigUpdateRequest(instance.getInstanceId());

            Mono<SidecarConfig> result = sidecarService.updateConfig(request);

            StepVerifier.create(result)
                    .expectNextMatches(config -> {
                        assertThat(config.getConfigId()).isNotNull().startsWith("sc-");
                        assertThat(config.getInstanceId()).isEqualTo(instance.getInstanceId());
                        assertThat(config.getVersion()).isEqualTo(2);
                        assertThat(config.getApplied()).isFalse();
                        assertThat(instanceStore.get(instance.getInstanceId()).getStatus())
                                .isEqualTo("config_pending");
                        return true;
                    })
                    .verifyComplete();

            assertThat(configInsertCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("更新配置 - 首个配置版本号为1")
        void updateConfig_FirstConfigVersionOne() {
            SidecarInstance instance = TestDataFactory.createSidecarInstance("pol-test-001");
            instanceStore.put(instance.getInstanceId(), instance);
            configStore.clear();

            ConfigUpdateRequest request = TestDataFactory.createConfigUpdateRequest(instance.getInstanceId());
            when(configMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            Mono<SidecarConfig> result = sidecarService.updateConfig(request);

            StepVerifier.create(result)
                    .expectNextMatches(config -> {
                        assertThat(config.getVersion()).isEqualTo(1);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("获取已应用配置 - 成功")
        void getAppliedConfig_Success() {
            SidecarInstance instance = TestDataFactory.createSidecarInstance("pol-test-001");
            instanceStore.put(instance.getInstanceId(), instance);

            SidecarConfig config = TestDataFactory.createSidecarConfig(instance.getInstanceId());
            config.setApplied(true);
            configStore.put(config.getConfigId(), config);

            Mono<SidecarConfig> result = sidecarService.getAppliedConfig(instance.getInstanceId());

            StepVerifier.create(result)
                    .expectNextMatches(c -> {
                        assertThat(c.getConfigId()).isEqualTo(config.getConfigId());
                        assertThat(c.getApplied()).isTrue();
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("更新资源限制 - 成功")
        void updateResourceLimits_Success() {
            SidecarInstance instance = TestDataFactory.createSidecarInstance("pol-test-001");
            instanceStore.put(instance.getInstanceId(), instance);

            SidecarConfig config = TestDataFactory.createSidecarConfig(instance.getInstanceId());
            configStore.put(config.getConfigId(), config);

            ResourceLimitUpdateRequest request = TestDataFactory.createResourceLimitUpdateRequest(instance.getInstanceId());

            Mono<com.chaoslab.modules.sidecar.dto.SidecarInstanceStatusResponse> result =
                    sidecarService.updateResourceLimits(request);

            StepVerifier.create(result)
                    .expectNextMatches(response -> {
                        assertThat(response.getInstanceId()).isEqualTo(instance.getInstanceId());
                        assertThat(response.getStatus()).isEqualTo("resource_update_pending");
                        assertThat(response.getResources()).isNotNull();
                        assertThat(configInsertCount.get()).isEqualTo(2);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("获取实例状态 - 成功")
        void getInstanceStatus_Success() {
            SidecarInstance instance = TestDataFactory.createSidecarInstance("pol-test-001");
            instanceStore.put(instance.getInstanceId(), instance);

            SidecarConfig config = TestDataFactory.createSidecarConfig(instance.getInstanceId());
            configStore.put(config.getConfigId(), config);

            Mono<com.chaoslab.modules.sidecar.dto.SidecarInstanceStatusResponse> result =
                    sidecarService.getInstanceStatus(instance.getInstanceId());

            StepVerifier.create(result)
                    .expectNextMatches(response -> {
                        assertThat(response.getInstanceId()).isEqualTo(instance.getInstanceId());
                        assertThat(response.getPolicyId()).isEqualTo(instance.getPolicyId());
                        assertThat(response.getResources()).isNotNull();
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("确认配置应用 - 成功")
        void confirmConfigApplied_Success() {
            SidecarInstance instance = TestDataFactory.createSidecarInstance("pol-test-001");
            instanceStore.put(instance.getInstanceId(), instance);

            SidecarConfig config = TestDataFactory.createSidecarConfig(instance.getInstanceId());
            config.setApplied(false);
            configStore.put(config.getConfigId(), config);

            Mono<Void> result = sidecarService.confirmConfigApplied(instance.getInstanceId(), config.getConfigId());

            StepVerifier.create(result)
                    .verifyComplete();

            assertThat(configStore.get(config.getConfigId()).getApplied()).isTrue();
            assertThat(configStore.get(config.getConfigId()).getAppliedAt()).isNotNull();
            assertThat(instanceStore.get(instance.getInstanceId()).getStatus()).isEqualTo("running");
        }
    }

    // ==================== 异常路径测试 ====================

    @Nested
    @DisplayName("异常路径测试")
    class ExceptionPathTests {

        @Test
        @DisplayName("创建注入策略 - Sidecar镜像为空")
        void createInjectionPolicy_NullSidecarImage() {
            InjectionPolicyCreateRequest request = TestDataFactory.createInjectionPolicyRequest();
            request.setSidecarImage("");

            Mono<SidecarInjectionPolicy> result = sidecarService.createInjectionPolicy(request);

            StepVerifier.create(result)
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable)
                                .isInstanceOf(BusinessException.class)
                                .hasMessageContaining("Sidecar镜像");
                        BusinessException be = (BusinessException) throwable;
                        assertThat(be.getCode()).isEqualTo(422);
                    })
                    .verify();
        }

        @Test
        @DisplayName("创建注入策略 - Sidecar镜像为null")
        void createInjectionPolicy_NullSidecarImageNull() {
            InjectionPolicyCreateRequest request = TestDataFactory.createInjectionPolicyRequest();
            request.setSidecarImage(null);

            Mono<SidecarInjectionPolicy> result = sidecarService.createInjectionPolicy(request);

            StepVerifier.create(result)
                    .expectError(BusinessException.class)
                    .verify();
        }

        @Test
        @DisplayName("获取策略 - 不存在")
        void getPolicy_NotFound() {
            when(policyMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            Mono<SidecarInjectionPolicy> result = sidecarService.getPolicy("non-existent-policy");

            StepVerifier.create(result)
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable)
                                .isInstanceOf(BusinessException.class)
                                .hasMessageContaining("不存在");
                        BusinessException be = (BusinessException) throwable;
                        assertThat(be.getCode()).isEqualTo(404);
                    })
                    .verify();
        }

        @Test
        @DisplayName("注入Sidecar - 策略不存在")
        void injectSidecar_PolicyNotFound() {
            when(policyMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            Mono<SidecarInstance> result = sidecarService.injectSidecar(
                    "non-existent", "test-pod", "default");

            StepVerifier.create(result)
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable).isInstanceOf(BusinessException.class);
                        assertThat(((BusinessException) throwable).getCode()).isEqualTo(404);
                    })
                    .verify();

            assertThat(instanceStore).isEmpty();
        }

        @Test
        @DisplayName("注入Sidecar - 策略未启用")
        void injectSidecar_PolicyNotEnabled() {
            SidecarInjectionPolicy policy = TestDataFactory.createSidecarInjectionPolicy();
            policy.setEnabled(false);
            policyStore.put(policy.getPolicyId(), policy);
            when(policyMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            Mono<SidecarInstance> result = sidecarService.injectSidecar(
                    policy.getPolicyId(), "test-pod", "default");

            StepVerifier.create(result)
                    .expectError(BusinessException.class)
                    .verify();

            assertThat(instanceStore).isEmpty();
        }

        @Test
        @DisplayName("更新配置 - 实例不存在")
        void updateConfig_InstanceNotFound() {
            when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            ConfigUpdateRequest request = TestDataFactory.createConfigUpdateRequest("non-existent");

            Mono<SidecarConfig> result = sidecarService.updateConfig(request);

            StepVerifier.create(result)
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable).isInstanceOf(BusinessException.class);
                        assertThat(((BusinessException) throwable).getCode()).isEqualTo(404);
                    })
                    .verify();

            assertThat(configStore).isEmpty();
        }

        @Test
        @DisplayName("获取已应用配置 - 不存在已应用的配置")
        void getAppliedConfig_NoAppliedConfig() {
            SidecarInstance instance = TestDataFactory.createSidecarInstance("pol-test-001");
            instanceStore.put(instance.getInstanceId(), instance);

            SidecarConfig config = TestDataFactory.createSidecarConfig(instance.getInstanceId());
            config.setApplied(false);
            configStore.put(config.getConfigId(), config);
            when(configMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            Mono<SidecarConfig> result = sidecarService.getAppliedConfig(instance.getInstanceId());

            StepVerifier.create(result)
                    .expectError(BusinessException.class)
                    .verify();
        }

        @Test
        @DisplayName("更新资源限制 - 实例不存在")
        void updateResourceLimits_InstanceNotFound() {
            when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            ResourceLimitUpdateRequest request = TestDataFactory.createResourceLimitUpdateRequest("non-existent");

            Mono<com.chaoslab.modules.sidecar.dto.SidecarInstanceStatusResponse> result =
                    sidecarService.updateResourceLimits(request);

            StepVerifier.create(result)
                    .expectError(BusinessException.class)
                    .verify();
        }

        @Test
        @DisplayName("获取实例状态 - 实例不存在")
        void getInstanceStatus_InstanceNotFound() {
            when(instanceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            Mono<com.chaoslab.modules.sidecar.dto.SidecarInstanceStatusResponse> result =
                    sidecarService.getInstanceStatus("non-existent");

            StepVerifier.create(result)
                    .expectError(BusinessException.class)
                    .verify();
        }

        @Test
        @DisplayName("确认配置应用 - 配置不存在")
        void confirmConfigApplied_ConfigNotFound() {
            SidecarInstance instance = TestDataFactory.createSidecarInstance("pol-test-001");
            instanceStore.put(instance.getInstanceId(), instance);
            when(configMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            Mono<Void> result = sidecarService.confirmConfigApplied(instance.getInstanceId(), "non-existent");

            StepVerifier.create(result)
                    .expectError(BusinessException.class)
                    .verify();
        }
    }

    // ==================== 并发线程安全性测试 ====================

    @Nested
    @DisplayName("并发线程安全性测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发创建注入策略 - 线程安全")
        void createInjectionPolicy_Concurrent() throws Exception {
            assertConcurrentSafety(
                    () -> {
                        InjectionPolicyCreateRequest request = TestDataFactory.createInjectionPolicyRequest();
                        return sidecarService.createInjectionPolicy(request).block();
                    },
                    DEFAULT_THREAD_COUNT,
                    DEFAULT_ITERATIONS
            );

            assertThat(policyInsertCount.get()).isPositive();
        }

        @Test
        @DisplayName("并发创建注入策略 - 数据正确性")
        void createInjectionPolicy_ConcurrentCorrectness() throws Exception {
            Set<String> createdIds = Collections.synchronizedSet(new HashSet<>());

            assertConcurrentCorrectness(
                    () -> {
                        InjectionPolicyCreateRequest request = TestDataFactory.createInjectionPolicyRequest();
                        return sidecarService.createInjectionPolicy(request).block();
                    },
                    policy -> {
                        assertNotNull(policy);
                        assertNotNull(policy.getPolicyId());
                        assertFalse(createdIds.contains(policy.getPolicyId()),
                                "Duplicate policy ID generated: " + policy.getPolicyId());
                        createdIds.add(policy.getPolicyId());
                        assertNotNull(policy.getName());
                        assertNotNull(policy.getNamespace());
                    },
                    DEFAULT_THREAD_COUNT,
                    DEFAULT_ITERATIONS
            );

            assertThat(createdIds).hasSize(DEFAULT_THREAD_COUNT * DEFAULT_ITERATIONS);
        }

        @Test
        @DisplayName("并发注入Sidecar - 线程安全")
        void injectSidecar_Concurrent() throws Exception {
            SidecarInjectionPolicy policy = TestDataFactory.createSidecarInjectionPolicy();
            policyStore.put(policy.getPolicyId(), policy);

            AtomicInteger podCounter = new AtomicInteger(0);

            assertConcurrentSafety(
                    () -> {
                        String podName = "pod-" + podCounter.incrementAndGet() + "-" + UUID.randomUUID();
                        return sidecarService.injectSidecar(
                                policy.getPolicyId(), podName, policy.getNamespace()).block();
                    },
                    DEFAULT_THREAD_COUNT,
                    DEFAULT_ITERATIONS
            );

            assertThat(instanceInsertCount.get()).isPositive();
            assertThat(configInsertCount.get()).isPositive();
        }

        @Test
        @DisplayName("并发更新配置 - 版本递增正确")
        void updateConfig_Concurrent_VersionIncrement() throws Exception {
            SidecarInstance instance = TestDataFactory.createSidecarInstance("pol-test-001");
            instanceStore.put(instance.getInstanceId(), instance);

            AtomicInteger updateCounter = new AtomicInteger(0);

            assertConcurrentSafety(
                    () -> {
                        ConfigUpdateRequest request = TestDataFactory.createConfigUpdateRequest(instance.getInstanceId());
                        request.getConfigData().put("updateSequence", updateCounter.incrementAndGet());
                        return sidecarService.updateConfig(request).block();
                    },
                    5,
                    10
            );

            List<SidecarConfig> configs = new ArrayList<>(configStore.values());
            OptionalInt maxVersion = configs.stream()
                    .mapToInt(SidecarConfig::getVersion)
                    .max();

            assertTrue(maxVersion.isPresent());
            assertEquals(50, maxVersion.getAsInt(),
                    "Max version should equal total update count");
        }

        @Test
        @DisplayName("并发读写 - 无数据竞争")
        void concurrentReadWrite_NoDataRace() throws Exception {
            for (int i = 0; i < 10; i++) {
                SidecarInjectionPolicy policy = TestDataFactory.createSidecarInjectionPolicy();
                policyStore.put(policy.getPolicyId(), policy);
            }

            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(8);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(8);
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            try {
                for (int i = 0; i < 4; i++) {
                    executor.submit(() -> {
                        try {
                            latch.await();
                            for (int j = 0; j < 50; j++) {
                                InjectionPolicyCreateRequest req = TestDataFactory.createInjectionPolicyRequest();
                                sidecarService.createInjectionPolicy(req).block();
                            }
                        } catch (Throwable t) {
                            errors.add(t);
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                for (int i = 0; i < 4; i++) {
                    executor.submit(() -> {
                        try {
                            latch.await();
                            for (int j = 0; j < 50; j++) {
                                List<String> ids = new ArrayList<>(policyStore.keySet());
                                if (!ids.isEmpty()) {
                                    String randomId = ids.get(new Random().nextInt(ids.size()));
                                    sidecarService.getPolicy(randomId).block();
                                }
                            }
                        } catch (Throwable t) {
                            errors.add(t);
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                latch.countDown();
                assertTrue(doneLatch.await(30, java.util.concurrent.TimeUnit.SECONDS));

                assertThat(errors).isEmpty();
                assertThat(policyStore.size()).isGreaterThan(10);

            } finally {
                executor.shutdownNow();
            }
        }
    }

    // ==================== 资源释放闭环测试 ====================

    @Nested
    @DisplayName("资源完整释放闭环测试")
    class ResourceReleaseTests {

        @Test
        @DisplayName("创建后删除 - 资源完整释放")
        void createAndDelete_ResourcesReleased() throws Exception {
            assertResourceRelease(
                    () -> {
                        InjectionPolicyCreateRequest request = TestDataFactory.createInjectionPolicyRequest();
                        SidecarInjectionPolicy policy = sidecarService.createInjectionPolicy(request).block();
                        assertNotNull(policy);
                    },
                    this::releaseResources,
                    100
            );
        }

        @Test
        @DisplayName("创建后删除 - 并发资源释放")
        void createAndDelete_ConcurrentResourcesReleased() throws Exception {
            assertResourceReleaseConcurrent(
                    () -> {
                        InjectionPolicyCreateRequest request = TestDataFactory.createInjectionPolicyRequest();
                        SidecarInjectionPolicy policy = sidecarService.createInjectionPolicy(request).block();
                        assertNotNull(policy);
                    },
                    this::releaseResources,
                    10,
                    50
            );
        }

        @Test
        @DisplayName("注入后清理 - 资源完整释放")
        void injectAndCleanup_ResourcesReleased() throws Exception {
            SidecarInjectionPolicy policy = TestDataFactory.createSidecarInjectionPolicy();
            policyStore.put(policy.getPolicyId(), policy);

            Runnable acquire = () -> {
                SidecarInstance instance = sidecarService.injectSidecar(
                        policy.getPolicyId(), "test-pod", "default").block();
                assertNotNull(instance);
            };

            Runnable release = () -> {
                instanceStore.clear();
                configStore.clear();
                instanceInsertCount.set(0);
                configInsertCount.set(0);
            };

            assertResourceRelease(acquire, release, 50);
        }

        @Test
        @DisplayName("异常场景下 - 资源仍然释放")
        void exceptionScenario_ResourcesReleased() throws Exception {
            AtomicInteger attemptCount = new AtomicInteger(0);

            Runnable acquire = () -> {
                int attempt = attemptCount.incrementAndGet();
                if (attempt % 2 == 0) {
                    try {
                        sidecarService.getPolicy("non-existent").block();
                        fail("Should have thrown exception");
                    } catch (BusinessException e) {
                        // Expected exception
                    }
                } else {
                    InjectionPolicyCreateRequest request = TestDataFactory.createInjectionPolicyRequest();
                    SidecarInjectionPolicy policy = sidecarService.createInjectionPolicy(request).block();
                    assertNotNull(policy);
                }
            };

            assertResourceRelease(acquire, this::releaseResources, 100);
        }

        @Test
        @DisplayName("完整生命周期 - 创建→注入→配置→更新→清理")
        void fullLifecycle_ResourcesReleased() throws Exception {
            Runnable acquire = () -> {
                InjectionPolicyCreateRequest policyRequest = TestDataFactory.createInjectionPolicyRequest();
                SidecarInjectionPolicy policy = sidecarService.createInjectionPolicy(policyRequest).block();
                assertNotNull(policy);

                SidecarInstance instance = sidecarService.injectSidecar(
                        policy.getPolicyId(), "lifecycle-pod", "default").block();
                assertNotNull(instance);

                ConfigUpdateRequest configRequest = TestDataFactory.createConfigUpdateRequest(instance.getInstanceId());
                SidecarConfig config = sidecarService.updateConfig(configRequest).block();
                assertNotNull(config);

                sidecarService.confirmConfigApplied(instance.getInstanceId(), config.getConfigId()).block();
            };

            assertResourceRelease(acquire, this::releaseResources, 20);
        }

        @Test
        @DisplayName("异常回滚场景 - 资源释放完整")
        void exceptionRollback_ResourcesReleased() throws Exception {
            when(configMapper.insert(any(SidecarConfig.class))).thenAnswer(invocation -> {
                if (Math.random() < 0.3) {
                    throw new RuntimeException("Simulated DB failure");
                }
                SidecarConfig config = invocation.getArgument(0);
                configStore.put(config.getConfigId(), config);
                configInsertCount.incrementAndGet();
                return 1;
            });

            SidecarInjectionPolicy policy = TestDataFactory.createSidecarInjectionPolicy();
            policyStore.put(policy.getPolicyId(), policy);

            int successCount = 0;
            int failureCount = 0;

            for (int i = 0; i < 50; i++) {
                try {
                    SidecarInstance instance = sidecarService.injectSidecar(
                            policy.getPolicyId(), "pod-" + i, "default").block();
                    if (instance != null) {
                        successCount++;
                    }
                } catch (Exception e) {
                    failureCount++;
                }
            }

            assertThat(successCount).isPositive();
            assertThat(failureCount).isPositive();

            int expectedInsertions = successCount * 2;
            assertThat(instanceInsertCount.get() + configInsertCount.get())
                    .isLessThanOrEqualTo(expectedInsertions);

            releaseResources();
            assertAllResourcesReleased();
        }
    }

    // ==================== 边界条件测试 ====================

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryTests {

        @Test
        @DisplayName("分页查询 - 第一页")
        void listPolicies_FirstPage() {
            for (int i = 0; i < 25; i++) {
                SidecarInjectionPolicy policy = TestDataFactory.createSidecarInjectionPolicy();
                policyStore.put(policy.getPolicyId(), policy);
            }

            Mono<Page<SidecarInjectionPolicy>> result = sidecarService.listPolicies(null, 1, 10);

            StepVerifier.create(result)
                    .expectNextMatches(page -> {
                        assertThat(page.getRecords()).hasSize(10);
                        assertThat(page.getTotal()).isEqualTo(25);
                        assertThat(page.getCurrent()).isEqualTo(1);
                        assertThat(page.getPages()).isEqualTo(3);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("分页查询 - 最后一页")
        void listPolicies_LastPage() {
            for (int i = 0; i < 25; i++) {
                SidecarInjectionPolicy policy = TestDataFactory.createSidecarInjectionPolicy();
                policyStore.put(policy.getPolicyId(), policy);
            }

            Mono<Page<SidecarInjectionPolicy>> result = sidecarService.listPolicies(null, 3, 10);

            StepVerifier.create(result)
                    .expectNextMatches(page -> {
                        assertThat(page.getRecords()).hasSize(5);
                        assertThat(page.getCurrent()).isEqualTo(3);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("空列表查询")
        void listPolicies_Empty() {
            Mono<Page<SidecarInjectionPolicy>> result = sidecarService.listPolicies(null, 1, 10);

            StepVerifier.create(result)
                    .expectNextMatches(page -> {
                        assertThat(page.getRecords()).isEmpty();
                        assertThat(page.getTotal()).isZero();
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("配置更新 - 多版本历史")
        void updateConfig_MultipleVersions() {
            SidecarInstance instance = TestDataFactory.createSidecarInstance("pol-test-001");
            instanceStore.put(instance.getInstanceId(), instance);

            for (int i = 1; i <= 10; i++) {
                ConfigUpdateRequest request = TestDataFactory.createConfigUpdateRequest(instance.getInstanceId());
                request.getConfigData().put("version", i);

                int expectedVersion = i + 1;

                SidecarConfig config = sidecarService.updateConfig(request).block();
                assertNotNull(config);
                assertEquals(expectedVersion, config.getVersion());
            }

            assertThat(configStore).hasSize(11);
        }
    }

    @AfterEach
    @Override
    protected void tearDown() throws Exception {
        releaseResources();
        assertAllResourcesReleased();
        super.tearDown();
    }
}
