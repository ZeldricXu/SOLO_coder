package com.chaoslab.modules.dns.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.common.ConcurrentTestBase;
import com.chaoslab.common.TestDataFactory;
import com.chaoslab.entity.DnsCache;
import com.chaoslab.entity.DnsResolutionPolicy;
import com.chaoslab.entity.DnsUpstream;
import com.chaoslab.exception.BusinessException;
import com.chaoslab.mapper.DnsCacheMapper;
import com.chaoslab.mapper.DnsResolutionPolicyMapper;
import com.chaoslab.mapper.DnsUpstreamMapper;
import com.chaoslab.modules.dns.dto.DnsResolveRequest;
import com.chaoslab.modules.dns.dto.DnsResolveResponse;
import com.chaoslab.modules.dns.dto.ResolutionPolicyCreateRequest;
import com.chaoslab.modules.dns.dto.UpstreamCreateRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("DnsProxyService 单元测试")
@Execution(ExecutionMode.SAME_THREAD)
class DnsProxyServiceTest extends ConcurrentTestBase {

    @Mock
    private DnsUpstreamMapper upstreamMapper;

    @Mock
    private DnsResolutionPolicyMapper policyMapper;

    @Mock
    private DnsCacheMapper cacheMapper;

    @Spy
    @InjectMocks
    private DnsProxyService dnsService;

    private final Map<String, DnsUpstream> upstreamStore = new ConcurrentHashMap<>();
    private final Map<String, DnsResolutionPolicy> policyStore = new ConcurrentHashMap<>();
    private final Map<String, DnsCache> cacheStore = new ConcurrentHashMap<>();

    private final AtomicInteger upstreamInsertCount = new AtomicInteger(0);
    private final AtomicInteger policyInsertCount = new AtomicInteger(0);
    private final AtomicInteger cacheInsertCount = new AtomicInteger(0);
    private final AtomicInteger cacheUpdateCount = new AtomicInteger(0);

    @BeforeEach
    @Override
    protected void setUp() {
        super.setUp();
        upstreamStore.clear();
        policyStore.clear();
        cacheStore.clear();
        upstreamInsertCount.set(0);
        policyInsertCount.set(0);
        cacheInsertCount.set(0);
        cacheUpdateCount.set(0);
        setupMockBehaviors();
        clearInternalCache();
    }

    @Override
    protected void assertAllResourcesReleased() {
        assertThat(upstreamStore).isEmpty();
        assertThat(policyStore).isEmpty();
        assertThat(cacheStore).isEmpty();
        assertThat(upstreamInsertCount.get()).isEqualTo(0);
        assertThat(policyInsertCount.get()).isEqualTo(0);
        assertThat(cacheInsertCount.get()).isEqualTo(0);
        assertThat(cacheUpdateCount.get()).isEqualTo(0);
    }

    private void clearInternalCache() {
        try {
            Field localCacheField = DnsProxyService.class.getDeclaredField("localCache");
            localCacheField.setAccessible(true);
            com.github.benmanes.caffeine.cache.Cache<?, ?> localCache =
                    (com.github.benmanes.caffeine.cache.Cache<?, ?>) localCacheField.get(dnsService);
            localCache.invalidateAll();
            localCache.cleanUp();

            Field roundRobinField = DnsProxyService.class.getDeclaredField("roundRobinCounters");
            roundRobinField.setAccessible(true);
            Map<?, ?> roundRobinCounters = (Map<?, ?>) roundRobinField.get(dnsService);
            roundRobinCounters.clear();
        } catch (Exception e) {
            throw new RuntimeException("Failed to clear internal cache", e);
        }
    }

    private void setupMockBehaviors() {
        when(upstreamMapper.insert(any(DnsUpstream.class))).thenAnswer(invocation -> {
            DnsUpstream upstream = invocation.getArgument(0);
            upstreamStore.put(upstream.getUpstreamId(), upstream);
            upstreamInsertCount.incrementAndGet();
            return 1;
        });

        when(upstreamMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return upstreamStore.values().stream().findFirst().orElse(null);
        });

        when(upstreamMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            LambdaQueryWrapper<DnsUpstream> wrapper = invocation.getArgument(0);
            List<DnsUpstream> upstreams = new ArrayList<>(upstreamStore.values());
            upstreams.sort(Comparator.comparing(DnsUpstream::getPriority));
            return upstreams;
        });

        when(upstreamMapper.selectCount(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return (long) upstreamStore.size();
        });

        when(upstreamMapper.updateById(any(DnsUpstream.class))).thenAnswer(invocation -> {
            DnsUpstream upstream = invocation.getArgument(0);
            upstreamStore.put(upstream.getUpstreamId(), upstream);
            return 1;
        });

        when(policyMapper.insert(any(DnsResolutionPolicy.class))).thenAnswer(invocation -> {
            DnsResolutionPolicy policy = invocation.getArgument(0);
            policyStore.put(policy.getPolicyId(), policy);
            policyInsertCount.incrementAndGet();
            return 1;
        });

        when(policyMapper.selectList(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            LambdaQueryWrapper<DnsResolutionPolicy> wrapper = invocation.getArgument(0);
            List<DnsResolutionPolicy> policies = new ArrayList<>(policyStore.values());
            policies.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
            return policies;
        });

        when(cacheMapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return cacheStore.values().stream().findFirst().orElse(null);
        });

        when(cacheMapper.selectCount(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            return (long) cacheStore.size();
        });

        when(cacheMapper.insert(any(DnsCache.class))).thenAnswer(invocation -> {
            DnsCache cache = invocation.getArgument(0);
            cacheStore.put(cache.getCacheId(), cache);
            cacheInsertCount.incrementAndGet();
            return 1;
        });

        when(cacheMapper.updateById(any(DnsCache.class))).thenAnswer(invocation -> {
            DnsCache cache = invocation.getArgument(0);
            cacheStore.put(cache.getCacheId(), cache);
            cacheUpdateCount.incrementAndGet();
            return 1;
        });

        when(cacheMapper.delete(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> {
            int deletedCount = cacheStore.size();
            cacheStore.clear();
            return deletedCount;
        });
    }

    private void releaseResources() {
        upstreamStore.clear();
        policyStore.clear();
        cacheStore.clear();
        upstreamInsertCount.set(0);
        policyInsertCount.set(0);
        cacheInsertCount.set(0);
        cacheUpdateCount.set(0);
        clearInternalCache();
    }

    // ==================== 正常路径测试 ====================

    @Nested
    @DisplayName("正常路径测试")
    class NormalPathTests {

        @Test
        @DisplayName("创建上游服务器 - 成功")
        void createUpstream_Success() {
            UpstreamCreateRequest request = TestDataFactory.createUpstreamCreateRequest();

            Mono<DnsUpstream> result = dnsService.createUpstream(request);

            StepVerifier.create(result)
                    .expectNextMatches(upstream -> {
                        assertThat(upstream.getUpstreamId()).isNotNull().startsWith("du-");
                        assertThat(upstream.getName()).isEqualTo(request.getName());
                        assertThat(upstream.getAddress()).isEqualTo(request.getAddress());
                        assertThat(upstream.getProtocol()).isEqualTo(request.getProtocol());
                        assertThat(upstream.getTimeoutMs()).isEqualTo(request.getTimeoutMs());
                        assertThat(upstream.getPriority()).isEqualTo(request.getPriority());
                        assertThat(upstream.getHealthCheckEnabled()).isTrue();
                        assertThat(upstream.getStatus()).isEqualTo("healthy");
                        assertThat(upstreamStore).containsKey(upstream.getUpstreamId());
                        return true;
                    })
                    .verifyComplete();

            verify(upstreamMapper, times(1)).insert(any(DnsUpstream.class));
            assertThat(upstreamInsertCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("查询上游服务器列表 - 无过滤")
        void listUpstreams_NoFilter() {
            for (int i = 0; i < 3; i++) {
                DnsUpstream upstream = TestDataFactory.createDnsUpstream();
                upstreamStore.put(upstream.getUpstreamId(), upstream);
            }

            Mono<List<DnsUpstream>> result = dnsService.listUpstreams(null);

            StepVerifier.create(result)
                    .expectNextMatches(upstreams -> {
                        assertThat(upstreams).hasSize(3);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("查询上游服务器列表 - 按状态过滤")
        void listUpstreams_ByStatus() {
            for (int i = 0; i < 3; i++) {
                DnsUpstream upstream = TestDataFactory.createDnsUpstream();
                upstreamStore.put(upstream.getUpstreamId(), upstream);
            }

            Mono<List<DnsUpstream>> result = dnsService.listUpstreams("healthy");

            StepVerifier.create(result)
                    .expectNextMatches(upstreams -> {
                        assertThat(upstreams).hasSize(3);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("创建解析策略 - 成功")
        void createPolicy_Success() {
            DnsUpstream upstream1 = TestDataFactory.createDnsUpstream();
            DnsUpstream upstream2 = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream1.getUpstreamId(), upstream1);
            upstreamStore.put(upstream2.getUpstreamId(), upstream2);

            when(upstreamMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

            ResolutionPolicyCreateRequest request = TestDataFactory.createResolutionPolicyCreateRequest();
            request.setUpstreamIds(Arrays.asList(upstream1.getUpstreamId(), upstream2.getUpstreamId()));

            Mono<DnsResolutionPolicy> result = dnsService.createPolicy(request);

            StepVerifier.create(result)
                    .expectNextMatches(policy -> {
                        assertThat(policy.getPolicyId()).isNotNull().startsWith("dp-");
                        assertThat(policy.getName()).isEqualTo(request.getName());
                        assertThat(policy.getDomainPattern()).isEqualTo(request.getDomainPattern());
                        assertThat(policy.getStrategy()).isEqualTo(request.getStrategy());
                        assertThat(policy.getCacheTtl()).isEqualTo(request.getCacheTtl());
                        assertThat(policy.getEnabled()).isTrue();
                        assertThat(policyStore).containsKey(policy.getPolicyId());
                        return true;
                    })
                    .verifyComplete();

            assertThat(policyInsertCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("创建解析策略 - 无上游ID")
        void createPolicy_NoUpstreamIds() {
            ResolutionPolicyCreateRequest request = TestDataFactory.createResolutionPolicyCreateRequest();
            request.setUpstreamIds(Collections.emptyList());

            Mono<DnsResolutionPolicy> result = dnsService.createPolicy(request);

            StepVerifier.create(result)
                    .expectNextMatches(policy -> {
                        assertThat(policy.getPolicyId()).isNotNull().startsWith("dp-");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("查询解析策略列表 - 成功")
        void listPolicies_Success() {
            for (int i = 0; i < 3; i++) {
                DnsResolutionPolicy policy = TestDataFactory.createDnsResolutionPolicy();
                policyStore.put(policy.getPolicyId(), policy);
            }

            Mono<List<DnsResolutionPolicy>> result = dnsService.listPolicies();

            StepVerifier.create(result)
                    .expectNextMatches(policies -> {
                        assertThat(policies).hasSize(3);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("DNS解析 - 成功（A记录，无缓存）")
        void resolve_Success_NotCached() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();

            Mono<DnsResolveResponse> result = dnsService.resolve(request);

            StepVerifier.create(result)
                    .expectNextMatches(response -> {
                        assertThat(response.getDomain()).isEqualTo(request.getDomain());
                        assertThat(response.getQueryType()).isEqualTo(request.getQueryType());
                        assertThat(response.getAnswers()).isNotNull();
                        assertThat(response.getTtl()).isEqualTo(300);
                        assertThat(response.getUpstreamId()).isEqualTo(upstream.getUpstreamId());
                        assertThat(response.getResolvedAt()).isNotNull();
                        assertThat(response.isFromCache()).isFalse();
                        return true;
                    })
                    .verifyComplete();

            assertThat(cacheInsertCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("DNS解析 - 成功（使用匹配的策略）")
        void resolve_Success_WithMatchingPolicy() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            DnsResolutionPolicy policy = TestDataFactory.createDnsResolutionPolicy();
            policy.setDomainPattern("*.example.com");
            policy.setCacheTtl(600);
            policy.setUpstreamIds(Collections.singletonList(upstream.getUpstreamId()));
            policyStore.put(policy.getPolicyId(), policy);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
            request.setDomain("api.example.com");

            Mono<DnsResolveResponse> result = dnsService.resolve(request);

            StepVerifier.create(result)
                    .expectNextMatches(response -> {
                        assertThat(response.getTtl()).isEqualTo(600);
                        assertThat(response.getUpstreamId()).isEqualTo(upstream.getUpstreamId());
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("DNS解析 - 成功（使用轮询策略）")
        void resolve_Success_RoundRobinStrategy() {
            for (int i = 0; i < 3; i++) {
                DnsUpstream upstream = TestDataFactory.createDnsUpstream();
                upstream.setPriority(i);
                upstreamStore.put(upstream.getUpstreamId(), upstream);
            }

            DnsResolutionPolicy policy = TestDataFactory.createDnsResolutionPolicy();
            policy.setDomainPattern("*");
            policy.setStrategy("round_robin");
            policyStore.put(policy.getPolicyId(), policy);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();

            Set<String> usedUpstreams = new HashSet<>();
            for (int i = 0; i < 6; i++) {
                DnsResolveResponse response = dnsService.resolve(request).block();
                assertNotNull(response);
                usedUpstreams.add(response.getUpstreamId());
            }

            assertThat(usedUpstreams).hasSize(3);
        }

        @Test
        @DisplayName("DNS解析 - 成功（使用优先级策略）")
        void resolve_Success_PriorityStrategy() {
            DnsUpstream upstream1 = TestDataFactory.createDnsUpstream();
            upstream1.setPriority(1);
            upstreamStore.put(upstream1.getUpstreamId(), upstream1);

            DnsUpstream upstream2 = TestDataFactory.createDnsUpstream();
            upstream2.setPriority(2);
            upstreamStore.put(upstream2.getUpstreamId(), upstream2);

            DnsResolutionPolicy policy = TestDataFactory.createDnsResolutionPolicy();
            policy.setDomainPattern("*");
            policy.setStrategy("priority");
            policyStore.put(policy.getPolicyId(), policy);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();

            for (int i = 0; i < 5; i++) {
                DnsResolveResponse response = dnsService.resolve(request).block();
                assertNotNull(response);
                assertThat(response.getUpstreamId()).isEqualTo(upstream1.getUpstreamId());
            }
        }

        @Test
        @DisplayName("DNS解析 - 成功（使用随机策略）")
        void resolve_Success_RandomStrategy() {
            for (int i = 0; i < 5; i++) {
                DnsUpstream upstream = TestDataFactory.createDnsUpstream();
                upstreamStore.put(upstream.getUpstreamId(), upstream);
            }

            DnsResolutionPolicy policy = TestDataFactory.createDnsResolutionPolicy();
            policy.setDomainPattern("*");
            policy.setStrategy("random");
            policyStore.put(policy.getPolicyId(), policy);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();

            Set<String> usedUpstreams = new HashSet<>();
            for (int i = 0; i < 20; i++) {
                DnsResolveResponse response = dnsService.resolve(request).block();
                assertNotNull(response);
                usedUpstreams.add(response.getUpstreamId());
            }

            assertThat(usedUpstreams.size()).isGreaterThan(1);
        }

        @Test
        @DisplayName("DNS解析 - 成功（使用延迟策略）")
        void resolve_Success_LatencyStrategy() {
            for (int i = 0; i < 3; i++) {
                DnsUpstream upstream = TestDataFactory.createDnsUpstream();
                upstreamStore.put(upstream.getUpstreamId(), upstream);
            }

            DnsResolutionPolicy policy = TestDataFactory.createDnsResolutionPolicy();
            policy.setDomainPattern("*");
            policy.setStrategy("latency");
            policyStore.put(policy.getPolicyId(), policy);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();

            Mono<DnsResolveResponse> result = dnsService.resolve(request);

            StepVerifier.create(result)
                    .expectNextMatches(response -> {
                        assertThat(response.getAnswers()).isNotNull();
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("DNS解析 - 缓存命中")
        void resolve_CacheHit() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
            DnsResolveResponse response1 = dnsService.resolve(request).block();
            assertNotNull(response1);
            assertThat(response1.isFromCache()).isFalse();

            DnsResolveResponse response2 = dnsService.resolve(request).block();
            assertNotNull(response2);
            assertThat(response2.isFromCache()).isTrue();
            assertThat(response2.getAnswers()).isEqualTo(response1.getAnswers());
        }

        @Test
        @DisplayName("DNS解析 - 强制刷新缓存")
        void resolve_ForceRefresh() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            DnsResolveRequest request1 = TestDataFactory.createDnsResolveRequest();
            dnsService.resolve(request1).block();

            DnsResolveRequest request2 = TestDataFactory.createDnsResolveRequest();
            request2.setForceRefresh(true);

            Mono<DnsResolveResponse> result = dnsService.resolve(request2);

            StepVerifier.create(result)
                    .expectNextMatches(response -> {
                        assertThat(response.isFromCache()).isFalse();
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("域名匹配 - 通配符模式")
        void domainMatches_Wildcard() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            DnsResolutionPolicy policy = TestDataFactory.createDnsResolutionPolicy();
            policy.setDomainPattern("*.example.com");
            policyStore.put(policy.getPolicyId(), policy);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
            request.setDomain("sub.example.com");

            Mono<DnsResolveResponse> result = dnsService.resolve(request);

            StepVerifier.create(result)
                    .expectNextMatches(response -> {
                        assertThat(response.getDomain()).isEqualTo("sub.example.com");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("健康检查 - 上游服务器健康")
        void healthCheckUpstreams_Healthy() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstream.setStatus("healthy");
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            dnsService.healthCheckUpstreams();

            assertThat(upstreamStore.get(upstream.getUpstreamId()).getStatus()).isEqualTo("healthy");
        }

        @Test
        @DisplayName("清理过期缓存 - 成功")
        void purgeExpiredCache_Success() {
            for (int i = 0; i < 5; i++) {
                DnsCache cache = new DnsCache();
                cache.setCacheId("dc-test-" + i);
                cache.setExpiresAt(LocalDateTime.now().minusDays(1));
                cacheStore.put(cache.getCacheId(), cache);
            }

            dnsService.purgeExpiredCache();

            assertThat(cacheStore).isEmpty();
        }

        @Test
        @DisplayName("获取缓存统计 - 成功")
        void getCacheStats_Success() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
            dnsService.resolve(request).block();

            Mono<Map<String, Object>> result = dnsService.getCacheStats();

            StepVerifier.create(result)
                    .expectNextMatches(stats -> {
                        assertThat(stats).containsKey("localCacheSize");
                        assertThat(stats).containsKey("persistentCacheCount");
                        assertThat((Long) stats.get("persistentCacheCount")).isGreaterThan(0);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("缓存持久化 - 更新已有缓存")
        void persistDnsCache_UpdateExisting() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();

            DnsResolveResponse response1 = dnsService.resolve(request).block();
            assertNotNull(response1);
            int initialHitCount = cacheStore.values().stream()
                    .mapToInt(DnsCache::getHitCount)
                    .sum();
            assertThat(initialHitCount).isEqualTo(1);

            DnsResolveResponse response2 = dnsService.resolve(request).block();
            assertNotNull(response2);

            DnsResolveResponse response3 = dnsService.resolve(request).block();
            assertNotNull(response3);

            assertThat(cacheUpdateCount.get()).isGreaterThan(0);
        }
    }

    // ==================== 异常路径测试 ====================

    @Nested
    @DisplayName("异常路径测试")
    class ExceptionPathTests {

        @Test
        @DisplayName("创建解析策略 - 上游不存在")
        void createPolicy_UpstreamNotFound() {
            ResolutionPolicyCreateRequest request = TestDataFactory.createResolutionPolicyCreateRequest();
            request.setUpstreamIds(Collections.singletonList("non-existent"));
            when(upstreamMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

            Mono<DnsResolutionPolicy> result = dnsService.createPolicy(request);

            StepVerifier.create(result)
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable)
                                .isInstanceOf(BusinessException.class)
                                .hasMessageContaining("不存在");
                        BusinessException be = (BusinessException) throwable;
                        assertThat(be.getCode()).isEqualTo(422);
                    })
                    .verify();

            assertThat(policyStore).isEmpty();
        }

        @Test
        @DisplayName("DNS解析 - 没有可用上游")
        void resolve_NoHealthyUpstreams() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstream.setStatus("unhealthy");
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            when(upstreamMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();

            Mono<DnsResolveResponse> result = dnsService.resolve(request);

            StepVerifier.create(result)
                    .expectErrorSatisfies(throwable -> {
                        assertThat(throwable)
                                .isInstanceOf(BusinessException.class)
                                .hasMessageContaining("没有可用的DNS上游服务器");
                        BusinessException be = (BusinessException) throwable;
                        assertThat(be.getCode()).isEqualTo(504);
                    })
                    .verify();
        }

        @Test
        @DisplayName("DNS解析 - 解析失败")
        void resolve_LookupFailure() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
            request.setDomain("nonexistent.invalid.domain.that.should.not.resolve.com");

            Mono<DnsResolveResponse> result = dnsService.resolve(request);

            StepVerifier.create(result)
                    .expectError(BusinessException.class)
                    .verify();
        }

        @Test
        @DisplayName("DNS解析 - 非A记录查询")
        void resolve_NonARecordQuery() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
            request.setQueryType("MX");

            Mono<DnsResolveResponse> result = dnsService.resolve(request);

            StepVerifier.create(result)
                    .expectNextMatches(response -> {
                        assertThat(response.getAnswers()).isEmpty();
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("未知策略 - 默认使用轮询")
        void selectUpstream_UnknownStrategy() {
            for (int i = 0; i < 3; i++) {
                DnsUpstream upstream = TestDataFactory.createDnsUpstream();
                upstreamStore.put(upstream.getUpstreamId(), upstream);
            }

            DnsResolutionPolicy policy = TestDataFactory.createDnsResolutionPolicy();
            policy.setDomainPattern("*");
            policy.setStrategy("unknown_strategy");
            policyStore.put(policy.getPolicyId(), policy);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();

            Set<String> usedUpstreams = new HashSet<>();
            for (int i = 0; i < 6; i++) {
                DnsResolveResponse response = dnsService.resolve(request).block();
                assertNotNull(response);
                usedUpstreams.add(response.getUpstreamId());
            }

            assertThat(usedUpstreams).hasSize(3);
        }

        @Test
        @DisplayName("空上游列表 - 抛出异常")
        void resolve_EmptyUpstreamList() {
            when(upstreamMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(Collections.emptyList());

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();

            Mono<DnsResolveResponse> result = dnsService.resolve(request);

            StepVerifier.create(result)
                    .expectError(BusinessException.class)
                    .verify();
        }

        @Test
        @DisplayName("域名不匹配任何策略 - 使用默认策略")
        void domainMatches_NoMatch_UsesDefault() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            DnsResolutionPolicy policy = TestDataFactory.createDnsResolutionPolicy();
            policy.setDomainPattern("specific.com");
            policyStore.put(policy.getPolicyId(), policy);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
            request.setDomain("other-domain.com");

            Mono<DnsResolveResponse> result = dnsService.resolve(request);

            StepVerifier.create(result)
                    .expectNextMatches(response -> {
                        assertThat(response.getTtl()).isEqualTo(300);
                        return true;
                    })
                    .verifyComplete();
        }
    }

    // ==================== 并发线程安全性测试 ====================

    @Nested
    @DisplayName("并发线程安全性测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发创建上游 - 线程安全")
        void createUpstream_Concurrent() throws Exception {
            assertConcurrentSafety(
                    () -> {
                        UpstreamCreateRequest request = TestDataFactory.createUpstreamCreateRequest();
                        return dnsService.createUpstream(request).block();
                    },
                    DEFAULT_THREAD_COUNT,
                    DEFAULT_ITERATIONS
            );

            assertThat(upstreamInsertCount.get()).isPositive();
        }

        @Test
        @DisplayName("并发创建上游 - ID不重复")
        void createUpstream_Concurrent_NoDuplicateIds() throws Exception {
            Set<String> createdIds = Collections.synchronizedSet(new HashSet<>());

            assertConcurrentCorrectness(
                    () -> {
                        UpstreamCreateRequest request = TestDataFactory.createUpstreamCreateRequest();
                        return dnsService.createUpstream(request).block();
                    },
                    upstream -> {
                        assertNotNull(upstream);
                        assertNotNull(upstream.getUpstreamId());
                        assertFalse(createdIds.contains(upstream.getUpstreamId()),
                                "Duplicate upstream ID: " + upstream.getUpstreamId());
                        createdIds.add(upstream.getUpstreamId());
                    },
                    DEFAULT_THREAD_COUNT,
                    DEFAULT_ITERATIONS
            );

            assertThat(createdIds).hasSize(DEFAULT_THREAD_COUNT * DEFAULT_ITERATIONS);
        }

        @Test
        @DisplayName("并发DNS解析 - 线程安全")
        void resolve_Concurrent() throws Exception {
            for (int i = 0; i < 5; i++) {
                DnsUpstream upstream = TestDataFactory.createDnsUpstream();
                upstreamStore.put(upstream.getUpstreamId(), upstream);
            }

            assertConcurrentSafety(
                    () -> {
                        DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
                        return dnsService.resolve(request).block();
                    },
                    10,
                    50
            );

            assertThat(cacheInsertCount.get()).isPositive();
        }

        @Test
        @DisplayName("并发轮询选择 - 分布均匀")
        void resolve_Concurrent_RoundRobinDistribution() throws Exception {
            int upstreamCount = 3;
            List<String> upstreamIds = new ArrayList<>();

            for (int i = 0; i < upstreamCount; i++) {
                DnsUpstream upstream = TestDataFactory.createDnsUpstream();
                upstream.setPriority(i);
                upstreamStore.put(upstream.getUpstreamId(), upstream);
                upstreamIds.add(upstream.getUpstreamId());
            }

            DnsResolutionPolicy policy = TestDataFactory.createDnsResolutionPolicy();
            policy.setDomainPattern("*");
            policy.setStrategy("round_robin");
            policyStore.put(policy.getPolicyId(), policy);

            int totalRequests = 300;
            Map<String, AtomicInteger> usageCount = new ConcurrentHashMap<>();
            upstreamIds.forEach(id -> usageCount.put(id, new AtomicInteger(0)));

            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(10);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(10);

            try {
                for (int i = 0; i < 10; i++) {
                    executor.submit(() -> {
                        try {
                            latch.await();
                            for (int j = 0; j < 30; j++) {
                                DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
                                DnsResolveResponse response = dnsService.resolve(request).block();
                                if (response != null) {
                                    usageCount.get(response.getUpstreamId()).incrementAndGet();
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                latch.countDown();
                assertTrue(doneLatch.await(60, java.util.concurrent.TimeUnit.SECONDS));

                for (String id : upstreamIds) {
                    int count = usageCount.get(id).get();
                    assertThat(count)
                            .as("Upstream " + id + " usage count should be ~100")
                            .isBetween(90, 110);
                }

            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("并发缓存访问 - 无缓存击穿")
        void resolve_Concurrent_CacheAccess_NoBreakdown() throws Exception {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            int threadCount = 20;
            int iterations = 50;
            AtomicInteger cacheHits = new AtomicInteger(0);
            AtomicInteger cacheMisses = new AtomicInteger(0);

            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(threadCount);

            try {
                for (int i = 0; i < threadCount; i++) {
                    final int threadIdx = i;
                    executor.submit(() -> {
                        try {
                            latch.await();
                            for (int j = 0; j < iterations; j++) {
                                String domain = "api" + (threadIdx % 5) + ".example.com";
                                DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
                                request.setDomain(domain);
                                DnsResolveResponse response = dnsService.resolve(request).block();
                                if (response != null) {
                                    if (response.isFromCache()) {
                                        cacheHits.incrementAndGet();
                                    } else {
                                        cacheMisses.incrementAndGet();
                                    }
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }

                latch.countDown();
                assertTrue(doneLatch.await(60, java.util.concurrent.TimeUnit.SECONDS));

                System.out.printf("Cache Hits: %d, Misses: %d, Hit Rate: %.2f%%%n",
                        cacheHits.get(), cacheMisses.get(),
                        (cacheHits.get() * 100.0) / (cacheHits.get() + cacheMisses.get()));

                assertThat(cacheMisses.get()).isLessThanOrEqualTo(5);

            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("并发读写缓存 - 数据一致性")
        void concurrentCacheReadWrite_DataConsistency() throws Exception {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            int threadCount = 8;
            int iterations = 100;
            List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch doneLatch = new java.util.concurrent.CountDownLatch(threadCount);

            try {
                for (int i = 0; i < 4; i++) {
                    executor.submit(() -> {
                        try {
                            latch.await();
                            for (int j = 0; j < iterations; j++) {
                                String domain = "test" + j + ".example.com";
                                DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
                                request.setDomain(domain);
                                DnsResolveResponse response = dnsService.resolve(request).block();
                                if (response == null || response.getAnswers() == null) {
                                    errors.add(new IllegalStateException("Invalid response"));
                                }
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
                            for (int j = 0; j < iterations; j++) {
                                String domain = "test" + j + ".example.com";
                                DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
                                request.setDomain(domain);
                                DnsResolveResponse response = dnsService.resolve(request).block();
                                if (response == null) {
                                    errors.add(new IllegalStateException("Cache read returned null"));
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
                assertTrue(doneLatch.await(60, java.util.concurrent.TimeUnit.SECONDS));

                assertThat(errors).isEmpty();

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
        @DisplayName("创建后清理 - 上游资源完整释放")
        void createAndCleanup_UpstreamResourcesReleased() throws Exception {
            Runnable acquire = () -> {
                UpstreamCreateRequest request = TestDataFactory.createUpstreamCreateRequest();
                DnsUpstream upstream = dnsService.createUpstream(request).block();
                assertNotNull(upstream);
            };

            assertResourceRelease(acquire, () -> {
                upstreamStore.clear();
                upstreamInsertCount.set(0);
                clearInternalCache();
            }, 100);
        }

        @Test
        @DisplayName("创建后清理 - 并发资源释放")
        void createAndCleanup_ConcurrentResourcesReleased() throws Exception {
            Runnable acquire = () -> {
                UpstreamCreateRequest request = TestDataFactory.createUpstreamCreateRequest();
                DnsUpstream upstream = dnsService.createUpstream(request).block();
                assertNotNull(upstream);
            };

            Runnable release = () -> {
                upstreamStore.clear();
                upstreamInsertCount.set(0);
                clearInternalCache();
            };

            assertResourceReleaseConcurrent(acquire, release, 10, 50);
        }

        @Test
        @DisplayName("解析后清理 - 缓存资源完整释放")
        void resolveAndCleanup_CacheResourcesReleased() throws Exception {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            Runnable acquire = () -> {
                DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
                DnsResolveResponse response = dnsService.resolve(request).block();
                assertNotNull(response);
            };

            Runnable release = () -> {
                cacheStore.clear();
                cacheInsertCount.set(0);
                cacheUpdateCount.set(0);
                clearInternalCache();
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
                    DnsUpstream upstream = TestDataFactory.createDnsUpstream();
                    upstreamStore.put(upstream.getUpstreamId(), upstream);

                    DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
                    DnsResolveResponse response = dnsService.resolve(request).block();
                    assertNotNull(response);
                } else {
                    ResolutionPolicyCreateRequest request = TestDataFactory.createResolutionPolicyCreateRequest();
                    request.setUpstreamIds(Collections.singletonList("non-existent"));
                    when(upstreamMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
                    try {
                        dnsService.createPolicy(request).block();
                        fail("Should have thrown exception");
                    } catch (BusinessException e) {
                        // Expected
                    }
                    when(upstreamMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
                }
            };

            assertResourceRelease(acquire, this::releaseResources, 60);
        }

        @Test
        @DisplayName("完整生命周期 - 创建上游→创建策略→解析→清理")
        void fullLifecycle_CreateResolveCleanup() throws Exception {
            Runnable acquire = () -> {
                UpstreamCreateRequest upstreamRequest = TestDataFactory.createUpstreamCreateRequest();
                DnsUpstream upstream = dnsService.createUpstream(upstreamRequest).block();
                assertNotNull(upstream);

                when(upstreamMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

                ResolutionPolicyCreateRequest policyRequest = TestDataFactory.createResolutionPolicyCreateRequest();
                policyRequest.setUpstreamIds(Collections.singletonList(upstream.getUpstreamId()));
                DnsResolutionPolicy policy = dnsService.createPolicy(policyRequest).block();
                assertNotNull(policy);

                DnsResolveRequest resolveRequest = TestDataFactory.createDnsResolveRequest();
                resolveRequest.setDomain("api.example.com");
                DnsResolveResponse response = dnsService.resolve(resolveRequest).block();
                assertNotNull(response);
            };

            assertResourceRelease(acquire, this::releaseResources, 20);
        }

        @Test
        @DisplayName("缓存过期清理 - 资源释放完整")
        void cacheExpiryPurge_ResourcesReleased() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            for (int i = 0; i < 20; i++) {
                DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
                request.setDomain("cache" + i + ".example.com");
                DnsResolveResponse response = dnsService.resolve(request).block();
                assertNotNull(response);
            }

            assertThat(cacheStore).hasSize(20);

            dnsService.purgeExpiredCache();

            assertThat(cacheStore).isEmpty();
            clearInternalCache();
            assertThat(upstreamStore).hasSize(1);

            releaseResources();
            assertAllResourcesReleased();
        }

        @Test
        @DisplayName("轮询计数器清理 - 资源释放完整")
        void roundRobinCounterCleanup_ResourcesReleased() throws Exception {
            for (int i = 0; i < 5; i++) {
                DnsUpstream upstream = TestDataFactory.createDnsUpstream();
                upstreamStore.put(upstream.getUpstreamId(), upstream);
            }

            DnsResolutionPolicy policy = TestDataFactory.createDnsResolutionPolicy();
            policy.setDomainPattern("*");
            policy.setStrategy("round_robin");
            policyStore.put(policy.getPolicyId(), policy);

            for (int i = 0; i < 100; i++) {
                DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
                DnsResolveResponse response = dnsService.resolve(request).block();
                assertNotNull(response);
            }

            try {
                Field roundRobinField = DnsProxyService.class.getDeclaredField("roundRobinCounters");
                roundRobinField.setAccessible(true);
                Map<?, ?> roundRobinCounters = (Map<?, ?>) roundRobinField.get(dnsService);
                assertThat(roundRobinCounters).isNotEmpty();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            releaseResources();

            try {
                Field roundRobinField = DnsProxyService.class.getDeclaredField("roundRobinCounters");
                roundRobinField.setAccessible(true);
                Map<?, ?> roundRobinCounters = (Map<?, ?>) roundRobinField.get(dnsService);
                assertThat(roundRobinCounters).isEmpty();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            assertAllResourcesReleased();
        }

        @Test
        @DisplayName("高并发解析后清理 - 无资源泄漏")
        void highConcurrencyResolve_NoResourceLeak() throws Exception {
            for (int i = 0; i < 5; i++) {
                DnsUpstream upstream = TestDataFactory.createDnsUpstream();
                upstreamStore.put(upstream.getUpstreamId(), upstream);
            }

            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(20);
            try {
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (int i = 0; i < 20; i++) {
                    futures.add(executor.submit(() -> {
                        for (int j = 0; j < 50; j++) {
                            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
                            dnsService.resolve(request).block();
                        }
                    }));
                }

                for (java.util.concurrent.Future<?> future : futures) {
                    future.get(60, java.util.concurrent.TimeUnit.SECONDS);
                }

                int initialCacheSize = cacheStore.size();
                assertThat(initialCacheSize).isGreaterThan(0);

            } finally {
                executor.shutdownNow();
            }

            releaseResources();
            assertAllResourcesReleased();
        }
    }

    // ==================== 边界条件测试 ====================

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryTests {

        @Test
        @DisplayName("域名匹配 - 精确匹配")
        void domainMatches_ExactMatch() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            DnsResolutionPolicy policy = TestDataFactory.createDnsResolutionPolicy();
            policy.setDomainPattern("api.example.com");
            policyStore.put(policy.getPolicyId(), policy);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
            request.setDomain("api.example.com");

            Mono<DnsResolveResponse> result = dnsService.resolve(request);

            StepVerifier.create(result)
                    .expectNextMatches(response -> {
                        assertThat(response.getDomain()).isEqualTo("api.example.com");
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("域名匹配 - 子域匹配")
        void domainMatches_SubdomainMatch() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            DnsResolutionPolicy policy = TestDataFactory.createDnsResolutionPolicy();
            policy.setDomainPattern("example.com");
            policyStore.put(policy.getPolicyId(), policy);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
            request.setDomain("sub.example.com");

            Mono<DnsResolveResponse> result = dnsService.resolve(request);

            StepVerifier.create(result)
                    .expectNextMatches(response -> true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("域名匹配 - 通配符根域匹配")
        void domainMatches_WildcardRootMatch() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            DnsResolutionPolicy policy = TestDataFactory.createDnsResolutionPolicy();
            policy.setDomainPattern("*.example.com");
            policyStore.put(policy.getPolicyId(), policy);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
            request.setDomain("example.com");

            Mono<DnsResolveResponse> result = dnsService.resolve(request);

            StepVerifier.create(result)
                    .expectNextMatches(response -> true)
                    .verifyComplete();
        }

        @Test
        @DisplayName("上游优先级排序 - 正确")
        void listUpstreams_PriorityOrder() {
            int[] priorities = {3, 1, 2};
            for (int priority : priorities) {
                DnsUpstream upstream = TestDataFactory.createDnsUpstream();
                upstream.setPriority(priority);
                upstreamStore.put(upstream.getUpstreamId(), upstream);
            }

            Mono<List<DnsUpstream>> result = dnsService.listUpstreams(null);

            StepVerifier.create(result)
                    .expectNextMatches(upstreams -> {
                        assertThat(upstreams.get(0).getPriority()).isEqualTo(1);
                        assertThat(upstreams.get(1).getPriority()).isEqualTo(2);
                        assertThat(upstreams.get(2).getPriority()).isEqualTo(3);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("空缓存统计 - 正确")
        void getCacheStats_Empty() {
            Mono<Map<String, Object>> result = dnsService.getCacheStats();

            StepVerifier.create(result)
                    .expectNextMatches(stats -> {
                        assertThat((Long) stats.get("persistentCacheCount")).isEqualTo(0);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("多个策略匹配 - 选择最新创建的")
        void findMatchingPolicy_MultipleMatches_ChoosesLatest() {
            DnsUpstream upstream = TestDataFactory.createDnsUpstream();
            upstreamStore.put(upstream.getUpstreamId(), upstream);

            DnsResolutionPolicy policy1 = TestDataFactory.createDnsResolutionPolicy();
            policy1.setDomainPattern("*.example.com");
            policy1.setCacheTtl(300);
            policy1.setCreatedAt(LocalDateTime.now().minusDays(2));
            policyStore.put(policy1.getPolicyId(), policy1);

            DnsResolutionPolicy policy2 = TestDataFactory.createDnsResolutionPolicy();
            policy2.setDomainPattern("api.example.com");
            policy2.setCacheTtl(600);
            policy2.setCreatedAt(LocalDateTime.now().minusDays(1));
            policyStore.put(policy2.getPolicyId(), policy2);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();
            request.setDomain("api.example.com");

            Mono<DnsResolveResponse> result = dnsService.resolve(request);

            StepVerifier.create(result)
                    .expectNextMatches(response -> {
                        assertThat(response.getTtl()).isEqualTo(600);
                        return true;
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("轮询计数器重置 - 正确循环")
        void selectRoundRobin_CounterResets() {
            for (int i = 0; i < 3; i++) {
                DnsUpstream upstream = TestDataFactory.createDnsUpstream();
                upstream.setPriority(i);
                upstreamStore.put(upstream.getUpstreamId(), upstream);
            }

            DnsResolutionPolicy policy = TestDataFactory.createDnsResolutionPolicy();
            policy.setDomainPattern("*");
            policy.setStrategy("round_robin");
            policyStore.put(policy.getPolicyId(), policy);

            DnsResolveRequest request = TestDataFactory.createDnsResolveRequest();

            List<String> order = new ArrayList<>();
            for (int i = 0; i < 9; i++) {
                DnsResolveResponse response = dnsService.resolve(request).block();
                assertNotNull(response);
                order.add(response.getUpstreamId());
            }

            for (int i = 0; i < 3; i++) {
                assertThat(order.get(i)).isEqualTo(order.get(i + 3));
                assertThat(order.get(i + 3)).isEqualTo(order.get(i + 6));
            }
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
