package com.chaoslab.modules.dns.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.common.OptimisticRetry;
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
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.net.InetAddress;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class DnsProxyService {

    private final DnsUpstreamMapper upstreamMapper;
    private final DnsResolutionPolicyMapper policyMapper;
    private final DnsCacheMapper cacheMapper;

    private final Cache<String, DnsResolveResponse> localCache = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();

    private final Map<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<DnsUpstream> createUpstream(UpstreamCreateRequest request) {
        return Mono.fromCallable(() -> {
            DnsUpstream upstream = new DnsUpstream();
            upstream.setUpstreamId("du-" + UUID.randomUUID().toString().substring(0, 8));
            upstream.setName(request.getName());
            upstream.setAddress(request.getAddress());
            upstream.setProtocol(request.getProtocol());
            upstream.setTimeoutMs(request.getTimeoutMs());
            upstream.setPriority(request.getPriority());
            upstream.setHealthCheckEnabled(request.getHealthCheckEnabled());
            upstream.setStatus("healthy");

            upstreamMapper.insert(upstream);
            log.info("Created DNS upstream: {}", upstream.getUpstreamId());
            return upstream;
        });
    }

    public Mono<List<DnsUpstream>> listUpstreams(String status) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<DnsUpstream> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isEmpty()) {
                wrapper.eq(DnsUpstream::getStatus, status);
            }
            wrapper.orderByAsc(DnsUpstream::getPriority);
            return upstreamMapper.selectList(wrapper);
        });
    }

    @Transactional
    @OptimisticRetry(maxAttempts = 3)
    public Mono<DnsResolutionPolicy> createPolicy(ResolutionPolicyCreateRequest request) {
        return Mono.fromCallable(() -> {
            validateUpstreamIds(request.getUpstreamIds());

            DnsResolutionPolicy policy = new DnsResolutionPolicy();
            policy.setPolicyId("dp-" + UUID.randomUUID().toString().substring(0, 8));
            policy.setName(request.getName());
            policy.setDomainPattern(request.getDomainPattern());
            policy.setStrategy(request.getStrategy());
            policy.setUpstreamIds(request.getUpstreamIds());
            policy.setCacheTtl(request.getCacheTtl());
            policy.setEnabled(request.getEnabled());

            policyMapper.insert(policy);
            log.info("Created DNS resolution policy: {}", policy.getPolicyId());
            return policy;
        });
    }

    public Mono<List<DnsResolutionPolicy>> listPolicies() {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<DnsResolutionPolicy> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DnsResolutionPolicy::getEnabled, true)
                    .orderByDesc(DnsResolutionPolicy::getCreatedAt);
            return policyMapper.selectList(wrapper);
        });
    }

    public Mono<DnsResolveResponse> resolve(DnsResolveRequest request) {
        String cacheKey = request.getDomain() + ":" + request.getQueryType();

        if (!request.getForceRefresh()) {
            DnsResolveResponse cached = localCache.getIfPresent(cacheKey);
            if (cached != null && cached.getResolvedAt().plusSeconds(cached.getTtl()).isAfter(LocalDateTime.now())) {
                log.debug("DNS cache hit for: {}", cacheKey);
                updateCacheHitCount(cacheKey);
                cached.setFromCache(true);
                return Mono.just(cached);
            }
        }

        return resolveWithPolicy(request)
                .doOnNext(response -> {
                    response.setFromCache(false);
                    response.setResolvedAt(LocalDateTime.now());
                    localCache.put(cacheKey, response);
                    persistDnsCache(request, response);
                });
    }

    private Mono<DnsResolveResponse> resolveWithPolicy(DnsResolveRequest request) {
        return Mono.fromCallable(() -> {
            DnsResolutionPolicy policy = findMatchingPolicy(request.getDomain());
            List<DnsUpstream> upstreams = getHealthyUpstreams(policy);

            if (upstreams.isEmpty()) {
                throw BusinessException.timeout("没有可用的DNS上游服务器");
            }

            DnsUpstream selectedUpstream = selectUpstream(upstreams, policy.getStrategy());
            List<String> answers = performDnsLookup(request.getDomain(), request.getQueryType(), selectedUpstream);

            DnsResolveResponse response = new DnsResolveResponse();
            response.setDomain(request.getDomain());
            response.setQueryType(request.getQueryType());
            response.setAnswers(answers);
            response.setTtl(policy.getCacheTtl() != null ? policy.getCacheTtl() : 300);
            response.setUpstreamId(selectedUpstream.getUpstreamId());
            response.setResolvedAt(LocalDateTime.now());

            log.info("Resolved {} via upstream {}: {}", request.getDomain(), selectedUpstream.getAddress(), answers);
            return response;
        });
    }

    private DnsResolutionPolicy findMatchingPolicy(String domain) {
        List<DnsResolutionPolicy> policies = policyMapper.selectList(
                new LambdaQueryWrapper<DnsResolutionPolicy>()
                        .eq(DnsResolutionPolicy::getEnabled, true)
                        .orderByDesc(DnsResolutionPolicy::getCreatedAt));

        for (DnsResolutionPolicy policy : policies) {
            if (domainMatches(domain, policy.getDomainPattern())) {
                return policy;
            }
        }

        DnsResolutionPolicy defaultPolicy = new DnsResolutionPolicy();
        defaultPolicy.setStrategy("round_robin");
        defaultPolicy.setCacheTtl(300);
        defaultPolicy.setUpstreamIds(getAllHealthyUpstreamIds());
        return defaultPolicy;
    }

    private boolean domainMatches(String domain, String pattern) {
        if (pattern.startsWith("*.")) {
            String suffix = pattern.substring(1);
            return domain.endsWith(suffix) || domain.equals(suffix.substring(1));
        }
        return domain.equals(pattern) || domain.endsWith("." + pattern);
    }

    private List<DnsUpstream> getHealthyUpstreams(DnsResolutionPolicy policy) {
        if (policy.getUpstreamIds() == null || policy.getUpstreamIds().isEmpty()) {
            return upstreamMapper.selectList(
                    new LambdaQueryWrapper<DnsUpstream>()
                            .eq(DnsUpstream::getStatus, "healthy")
                            .orderByAsc(DnsUpstream::getPriority));
        }

        return upstreamMapper.selectList(
                new LambdaQueryWrapper<DnsUpstream>()
                        .in(DnsUpstream::getUpstreamId, policy.getUpstreamIds())
                        .eq(DnsUpstream::getStatus, "healthy")
                        .orderByAsc(DnsUpstream::getPriority));
    }

    private List<String> getAllHealthyUpstreamIds() {
        return upstreamMapper.selectList(
                        new LambdaQueryWrapper<DnsUpstream>()
                                .eq(DnsUpstream::getStatus, "healthy"))
                .stream()
                .map(DnsUpstream::getUpstreamId)
                .toList();
    }

    private DnsUpstream selectUpstream(List<DnsUpstream> upstreams, String strategy) {
        return switch (strategy) {
            case "round_robin" -> selectRoundRobin(upstreams);
            case "priority" -> upstreams.get(0);
            case "random" -> upstreams.get(new Random().nextInt(upstreams.size()));
            case "latency" -> selectByLatency(upstreams);
            default -> selectRoundRobin(upstreams);
        };
    }

    private DnsUpstream selectRoundRobin(List<DnsUpstream> upstreams) {
        String key = String.join(",", upstreams.stream().map(DnsUpstream::getUpstreamId).toList());
        AtomicInteger counter = roundRobinCounters.computeIfAbsent(key, k -> new AtomicInteger(0));
        int index = counter.getAndIncrement() % upstreams.size();
        return upstreams.get(index);
    }

    private DnsUpstream selectByLatency(List<DnsUpstream> upstreams) {
        DnsUpstream best = null;
        long minLatency = Long.MAX_VALUE;

        for (DnsUpstream upstream : upstreams) {
            long latency = measureLatency(upstream);
            if (latency < minLatency) {
                minLatency = latency;
                best = upstream;
            }
        }

        return best != null ? best : upstreams.get(0);
    }

    private long measureLatency(DnsUpstream upstream) {
        long start = System.nanoTime();
        try {
            String[] parts = upstream.getAddress().split(":");
            InetAddress.getByName(parts[0]);
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
        return System.nanoTime() - start;
    }

    private List<String> performDnsLookup(String domain, String queryType, DnsUpstream upstream) {
        try {
            if ("A".equals(queryType)) {
                InetAddress[] addresses = InetAddress.getAllByName(domain);
                return Arrays.stream(addresses)
                        .map(InetAddress::getHostAddress)
                        .toList();
            }
            return Collections.emptyList();
        } catch (Exception e) {
            log.error("DNS lookup failed for {} via {}: {}", domain, upstream.getAddress(), e.getMessage());
            throw BusinessException.timeout("DNS解析失败: " + e.getMessage());
        }
    }

    @Transactional
    public void persistDnsCache(DnsResolveRequest request, DnsResolveResponse response) {
        String queryKey = request.getDomain() + ":" + request.getQueryType();

        LambdaQueryWrapper<DnsCache> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DnsCache::getQueryKey, queryKey);
        DnsCache existing = cacheMapper.selectOne(wrapper);

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("answers", response.getAnswers());
        responseData.put("upstreamId", response.getUpstreamId());

        if (existing != null) {
            existing.setResponseData(responseData);
            existing.setTtl((int) response.getTtl());
            existing.setExpiresAt(LocalDateTime.now().plusSeconds(response.getTtl()));
            existing.setHitCount(existing.getHitCount() + 1);
            cacheMapper.updateById(existing);
        } else {
            DnsCache cache = new DnsCache();
            cache.setCacheId("dc-" + UUID.randomUUID().toString().substring(0, 8));
            cache.setQueryKey(queryKey);
            cache.setQueryType(request.getQueryType());
            cache.setResponseData(responseData);
            cache.setTtl((int) response.getTtl());
            cache.setExpiresAt(LocalDateTime.now().plusSeconds(response.getTtl()));
            cache.setHitCount(1);
            cacheMapper.insert(cache);
        }
    }

    private void updateCacheHitCount(String queryKey) {
        LambdaQueryWrapper<DnsCache> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DnsCache::getQueryKey, queryKey);
        DnsCache cache = cacheMapper.selectOne(wrapper);
        if (cache != null) {
            cache.setHitCount(cache.getHitCount() + 1);
            cacheMapper.updateById(cache);
        }
    }

    private void validateUpstreamIds(List<String> upstreamIds) {
        if (upstreamIds == null || upstreamIds.isEmpty()) {
            return;
        }

        for (String upstreamId : upstreamIds) {
            LambdaQueryWrapper<DnsUpstream> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DnsUpstream::getUpstreamId, upstreamId);
            if (upstreamMapper.selectCount(wrapper) == 0) {
                throw BusinessException.validationError("上游服务器不存在: " + upstreamId);
            }
        }
    }

    @Scheduled(fixedRate = 30000)
    public void healthCheckUpstreams() {
        List<DnsUpstream> upstreams = upstreamMapper.selectList(
                new LambdaQueryWrapper<DnsUpstream>()
                        .eq(DnsUpstream::getHealthCheckEnabled, true));

        for (DnsUpstream upstream : upstreams) {
            boolean healthy = checkUpstreamHealth(upstream);
            String newStatus = healthy ? "healthy" : "unhealthy";
            if (!newStatus.equals(upstream.getStatus())) {
                upstream.setStatus(newStatus);
                upstreamMapper.updateById(upstream);
                log.warn("DNS upstream {} status changed to {}", upstream.getUpstreamId(), newStatus);
            }
        }
    }

    @Scheduled(cron = "0 0 * * * *")
    public void purgeExpiredCache() {
        LambdaQueryWrapper<DnsCache> wrapper = new LambdaQueryWrapper<>();
        wrapper.lt(DnsCache::getExpiresAt, LocalDateTime.now());
        cacheMapper.delete(wrapper);
        localCache.cleanUp();
        log.info("Purged expired DNS cache entries");
    }

    private boolean checkUpstreamHealth(DnsUpstream upstream) {
        try {
            String[] parts = upstream.getAddress().split(":");
            InetAddress.getByName("www.baidu.com");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public Mono<Map<String, Object>> getCacheStats() {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();
            stats.put("localCacheSize", localCache.estimatedSize());
            LambdaQueryWrapper<DnsCache> wrapper = new LambdaQueryWrapper<>();
            stats.put("persistentCacheCount", cacheMapper.selectCount(wrapper));
            return stats;
        });
    }
}
