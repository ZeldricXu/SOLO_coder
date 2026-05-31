package com.modelguard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelguard.dto.InferenceCallDTO;
import com.modelguard.dto.ModelProviderDTO;
import com.modelguard.dto.ModelRouteDTO;
import com.modelguard.entity.InferenceRequest;
import com.modelguard.entity.ModelProvider;
import com.modelguard.entity.ModelRoute;
import com.modelguard.exception.BusinessException;
import com.modelguard.exception.ResourceNotFoundException;
import com.modelguard.exception.TimeoutException;
import com.modelguard.mapper.InferenceRequestMapper;
import com.modelguard.mapper.ModelProviderMapper;
import com.modelguard.mapper.ModelRouteMapper;
import com.modelguard.service.InferenceGatewayService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatusCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class InferenceGatewayServiceImpl implements InferenceGatewayService {

    private final ModelProviderMapper modelProviderMapper;
    private final ModelRouteMapper modelRouteMapper;
    private final InferenceRequestMapper inferenceRequestMapper;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final WebClient.Builder webClientBuilder;

    private static final String PROVIDER_CACHE_PREFIX = "provider:";
    private static final String ROUTE_CACHE_PREFIX = "route:";
    private static final String CIRCUIT_BREAKER_PREFIX = "circuit_breaker:";
    private static final String LB_ROUND_ROBIN_COUNTER = "lb:rr:";

    private final Map<String, AtomicInteger> roundRobinCounters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> providerRequestCounts = new ConcurrentHashMap<>();

    private final Counter inferenceRequestCounter;
    private final Counter inferenceSuccessCounter;
    private final Counter inferenceFailureCounter;
    private final Counter inferenceFallbackCounter;
    private final Timer inferenceLatencyTimer;

    {
        inferenceRequestCounter = Counter.builder("inference.requests.total")
                .description("Total inference requests")
                .register(io.micrometer.core.instrument.Metrics.globalRegistry);
        inferenceSuccessCounter = Counter.builder("inference.requests.success")
                .description("Successful inference requests")
                .register(io.micrometer.core.instrument.Metrics.globalRegistry);
        inferenceFailureCounter = Counter.builder("inference.requests.failure")
                .description("Failed inference requests")
                .register(io.micrometer.core.instrument.Metrics.globalRegistry);
        inferenceFallbackCounter = Counter.builder("inference.requests.fallback")
                .description("Fallback inference requests")
                .register(io.micrometer.core.instrument.Metrics.globalRegistry);
        inferenceLatencyTimer = Timer.builder("inference.latency")
                .description("Inference latency")
                .register(io.micrometer.core.instrument.Metrics.globalRegistry);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<ModelProvider> registerProvider(ModelProviderDTO dto) {
        return Mono.fromCallable(() -> {
            String providerId = dto.getProviderId() != null ? dto.getProviderId() : "prov_" + IdUtil.simpleUUID();

            LambdaQueryWrapper<ModelProvider> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ModelProvider::getProviderId, providerId);
            if (modelProviderMapper.selectCount(wrapper) > 0) {
                throw new BusinessException("Provider ID already exists: " + providerId);
            }

            ModelProvider provider = new ModelProvider();
            provider.setProviderId(providerId);
            provider.setProviderName(dto.getProviderName());
            provider.setProviderType(dto.getProviderType());
            provider.setBaseUrl(dto.getBaseUrl());
            provider.setApiKey(dto.getApiKey());
            provider.setConfig(dto.getConfig());
            provider.setWeight(dto.getWeight() != null ? dto.getWeight() : 100);
            provider.setPriority(dto.getPriority() != null ? dto.getPriority() : 5);
            provider.setTimeoutMs(dto.getTimeoutMs() != null ? dto.getTimeoutMs() : 30000);
            provider.setMaxRetries(dto.getMaxRetries() != null ? dto.getMaxRetries() : 2);
            provider.setSupportedModels(dto.getSupportedModels());
            provider.setHealthCheckEndpoint(dto.getHealthCheckEndpoint());
            provider.setStatus("active");
            provider.setHealthStatus("unknown");
            provider.setSuccessRate(1.0);
            provider.setAvgLatencyMs(0.0);
            provider.setLastHealthCheckAt(System.currentTimeMillis());

            modelProviderMapper.insert(provider);

            String cacheKey = PROVIDER_CACHE_PREFIX + providerId;
            redisTemplate.opsForValue().set(cacheKey, toJson(provider), Duration.ofMinutes(10)).subscribe();

            return provider;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<ModelProvider> updateProvider(String providerId, ModelProviderDTO dto) {
        return getProvider(providerId)
                .flatMap(provider -> Mono.fromCallable(() -> {
                    if (dto.getProviderName() != null) provider.setProviderName(dto.getProviderName());
                    if (dto.getProviderType() != null) provider.setProviderType(dto.getProviderType());
                    if (dto.getBaseUrl() != null) provider.setBaseUrl(dto.getBaseUrl());
                    if (dto.getApiKey() != null) provider.setApiKey(dto.getApiKey());
                    if (dto.getConfig() != null) provider.setConfig(dto.getConfig());
                    if (dto.getWeight() != null) provider.setWeight(dto.getWeight());
                    if (dto.getPriority() != null) provider.setPriority(dto.getPriority());
                    if (dto.getTimeoutMs() != null) provider.setTimeoutMs(dto.getTimeoutMs());
                    if (dto.getMaxRetries() != null) provider.setMaxRetries(dto.getMaxRetries());
                    if (dto.getSupportedModels() != null) provider.setSupportedModels(dto.getSupportedModels());
                    if (dto.getHealthCheckEndpoint() != null) provider.setHealthCheckEndpoint(dto.getHealthCheckEndpoint());

                    modelProviderMapper.updateById(provider);

                    String cacheKey = PROVIDER_CACHE_PREFIX + providerId;
                    redisTemplate.opsForValue().set(cacheKey, toJson(provider), Duration.ofMinutes(10)).subscribe();

                    return provider;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Void> deleteProvider(String providerId) {
        return getProvider(providerId)
                .flatMap(provider -> Mono.fromCallable(() -> {
                    modelProviderMapper.deleteById(provider.getId());
                    String cacheKey = PROVIDER_CACHE_PREFIX + providerId;
                    redisTemplate.delete(cacheKey).subscribe();
                    return null;
                }).subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    @Override
    public Mono<ModelProvider> getProvider(String providerId) {
        String cacheKey = PROVIDER_CACHE_PREFIX + providerId;
        return redisTemplate.opsForValue().get(cacheKey)
                .flatMap(json -> Mono.justOrEmpty(fromJson(json, ModelProvider.class)))
                .switchIfEmpty(Mono.fromCallable(() -> {
                    LambdaQueryWrapper<ModelProvider> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(ModelProvider::getProviderId, providerId);
                    ModelProvider provider = modelProviderMapper.selectOne(wrapper);
                    if (provider == null) {
                        throw new ResourceNotFoundException("Provider not found: " + providerId);
                    }
                    redisTemplate.opsForValue().set(cacheKey, toJson(provider), Duration.ofMinutes(10)).subscribe();
                    return provider;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    public Mono<Page<ModelProvider>> listProviders(int page, int size, String status, String providerType) {
        return Mono.fromCallable(() -> {
            Page<ModelProvider> pageParam = new Page<>(page, size);
            LambdaQueryWrapper<ModelProvider> wrapper = new LambdaQueryWrapper<>();
            if (status != null) wrapper.eq(ModelProvider::getStatus, status);
            if (providerType != null) wrapper.eq(ModelProvider::getProviderType, providerType);
            wrapper.orderByDesc(ModelProvider::getCreatedAt);
            return modelProviderMapper.selectPage(pageParam, wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<ModelProvider>> getHealthyProviders() {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ModelProvider> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ModelProvider::getStatus, "active")
                    .eq(ModelProvider::getHealthStatus, "healthy");
            return modelProviderMapper.selectList(wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<ModelProvider> checkProviderHealth(String providerId) {
        return getProvider(providerId)
                .flatMap(provider -> {
                    if (provider.getHealthCheckEndpoint() == null) {
                        provider.setHealthStatus("healthy");
                        provider.setLastHealthCheckAt(System.currentTimeMillis());
                        return updateProviderHealth(provider);
                    }

                    String healthUrl = provider.getBaseUrl() + provider.getHealthCheckEndpoint();
                    return webClientBuilder.build().get().uri(healthUrl)
                            .header("Authorization", "Bearer " + provider.getApiKey())
                            .retrieve()
                            .toBodilessEntity()
                            .map(response -> {
                                provider.setHealthStatus("healthy");
                                provider.setLastHealthCheckAt(System.currentTimeMillis());
                                return provider;
                            })
                            .onErrorResume(e -> {
                                log.warn("Health check failed for provider {}: {}", providerId, e.getMessage());
                                provider.setHealthStatus("unhealthy");
                                provider.setLastHealthCheckAt(System.currentTimeMillis());
                                return Mono.just(provider);
                            })
                            .flatMap(this::updateProviderHealth);
                });
    }

    private Mono<ModelProvider> updateProviderHealth(ModelProvider provider) {
        return Mono.fromCallable(() -> {
            modelProviderMapper.updateById(provider);
            String cacheKey = PROVIDER_CACHE_PREFIX + provider.getProviderId();
            redisTemplate.opsForValue().set(cacheKey, toJson(provider), Duration.ofMinutes(10)).subscribe();
            return provider;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<ModelRoute> createRoute(ModelRouteDTO dto) {
        return Mono.fromCallable(() -> {
            String routeId = dto.getRouteId() != null ? dto.getRouteId() : "route_" + IdUtil.simpleUUID();

            LambdaQueryWrapper<ModelRoute> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ModelRoute::getRouteId, routeId);
            if (modelRouteMapper.selectCount(wrapper) > 0) {
                throw new BusinessException("Route ID already exists: " + routeId);
            }

            ModelRoute route = new ModelRoute();
            route.setRouteId(routeId);
            route.setRouteName(dto.getRouteName());
            route.setModelName(dto.getModelName());
            route.setPrimaryProviders(dto.getPrimaryProviders());
            route.setFallbackProviders(dto.getFallbackProviders());
            route.setLoadBalanceStrategy(dto.getLoadBalanceStrategy() != null ? dto.getLoadBalanceStrategy() : "round_robin");
            route.setEnableFallback(dto.getEnableFallback() != null ? dto.getEnableFallback() : true);
            route.setTimeoutMs(dto.getTimeoutMs() != null ? dto.getTimeoutMs() : 30000);
            route.setMaxRetries(dto.getMaxRetries() != null ? dto.getMaxRetries() : 2);
            route.setFailureThreshold(dto.getFailureThreshold() != null ? dto.getFailureThreshold() : 0.5);
            route.setCircuitBreakerOpenMs(dto.getCircuitBreakerOpenMs() != null ? dto.getCircuitBreakerOpenMs() : 60000);
            route.setRoutingRules(dto.getRoutingRules());
            route.setStatus("active");
            route.setDescription(dto.getDescription());

            modelRouteMapper.insert(route);

            String cacheKey = ROUTE_CACHE_PREFIX + routeId;
            redisTemplate.opsForValue().set(cacheKey, toJson(route), Duration.ofMinutes(10)).subscribe();

            return route;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<ModelRoute> updateRoute(String routeId, ModelRouteDTO dto) {
        return getRoute(routeId)
                .flatMap(route -> Mono.fromCallable(() -> {
                    if (dto.getRouteName() != null) route.setRouteName(dto.getRouteName());
                    if (dto.getModelName() != null) route.setModelName(dto.getModelName());
                    if (dto.getPrimaryProviders() != null) route.setPrimaryProviders(dto.getPrimaryProviders());
                    if (dto.getFallbackProviders() != null) route.setFallbackProviders(dto.getFallbackProviders());
                    if (dto.getLoadBalanceStrategy() != null) route.setLoadBalanceStrategy(dto.getLoadBalanceStrategy());
                    if (dto.getEnableFallback() != null) route.setEnableFallback(dto.getEnableFallback());
                    if (dto.getTimeoutMs() != null) route.setTimeoutMs(dto.getTimeoutMs());
                    if (dto.getMaxRetries() != null) route.setMaxRetries(dto.getMaxRetries());
                    if (dto.getFailureThreshold() != null) route.setFailureThreshold(dto.getFailureThreshold());
                    if (dto.getCircuitBreakerOpenMs() != null) route.setCircuitBreakerOpenMs(dto.getCircuitBreakerOpenMs());
                    if (dto.getRoutingRules() != null) route.setRoutingRules(dto.getRoutingRules());
                    if (dto.getDescription() != null) route.setDescription(dto.getDescription());

                    modelRouteMapper.updateById(route);

                    String cacheKey = ROUTE_CACHE_PREFIX + routeId;
                    redisTemplate.opsForValue().set(cacheKey, toJson(route), Duration.ofMinutes(10)).subscribe();

                    return route;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Void> deleteRoute(String routeId) {
        return getRoute(routeId)
                .flatMap(route -> Mono.fromCallable(() -> {
                    modelRouteMapper.deleteById(route.getId());
                    String cacheKey = ROUTE_CACHE_PREFIX + routeId;
                    redisTemplate.delete(cacheKey).subscribe();
                    return null;
                }).subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    @Override
    public Mono<ModelRoute> getRoute(String routeId) {
        String cacheKey = ROUTE_CACHE_PREFIX + routeId;
        return redisTemplate.opsForValue().get(cacheKey)
                .flatMap(json -> Mono.justOrEmpty(fromJson(json, ModelRoute.class)))
                .switchIfEmpty(Mono.fromCallable(() -> {
                    LambdaQueryWrapper<ModelRoute> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(ModelRoute::getRouteId, routeId);
                    ModelRoute route = modelRouteMapper.selectOne(wrapper);
                    if (route == null) {
                        throw new ResourceNotFoundException("Route not found: " + routeId);
                    }
                    redisTemplate.opsForValue().set(cacheKey, toJson(route), Duration.ofMinutes(10)).subscribe();
                    return route;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    public Mono<Page<ModelRoute>> listRoutes(int page, int size, String modelName, String status) {
        return Mono.fromCallable(() -> {
            Page<ModelRoute> pageParam = new Page<>(page, size);
            LambdaQueryWrapper<ModelRoute> wrapper = new LambdaQueryWrapper<>();
            if (modelName != null) wrapper.eq(ModelRoute::getModelName, modelName);
            if (status != null) wrapper.eq(ModelRoute::getStatus, status);
            wrapper.orderByDesc(ModelRoute::getCreatedAt);
            return modelRouteMapper.selectPage(pageParam, wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<ModelRoute> getRouteByModel(String modelName) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ModelRoute> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ModelRoute::getModelName, modelName)
                    .eq(ModelRoute::getStatus, "active")
                    .orderByDesc(ModelRoute::getCreatedAt);
            List<ModelRoute> routes = modelRouteMapper.selectList(wrapper);
            if (routes.isEmpty()) {
                throw new ResourceNotFoundException("No active route found for model: " + modelName);
            }
            return routes.get(0);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<String, Object>> callInference(InferenceCallDTO dto) {
        inferenceRequestCounter.increment();
        Timer.Sample sample = Timer.start();

        Mono<ModelRoute> routeMono;
        if (dto.getRouteId() != null) {
            routeMono = getRoute(dto.getRouteId());
        } else if (dto.getModelName() != null) {
            routeMono = getRouteByModel(dto.getModelName());
        } else {
            return Mono.error(new BusinessException("Either routeId or modelName must be provided"));
        }

        String requestId = "req_" + IdUtil.simpleUUID();
        LocalDateTime startedAt = LocalDateTime.now();
        InferenceRequest requestLog = new InferenceRequest();
        requestLog.setRequestId(requestId);
        requestLog.setModelName(dto.getModelName());
        requestLog.setUserId(dto.getUserId());
        requestLog.setTraceId(dto.getTraceId());
        requestLog.setRequestBody(dto.getRequestBody());
        requestLog.setStartedAt(startedAt);
        requestLog.setStatus("processing");

        return routeMono.flatMap(route -> {
            requestLog.setRouteId(route.getRouteId());
            List<String> providers = route.getPrimaryProviders();

            return callWithFallback(dto, route, providers, 0, null, requestLog)
                    .flatMap(result -> {
                        long latencyMs = Duration.between(startedAt, LocalDateTime.now()).toMillis();
                        sample.stop(inferenceLatencyTimer);
                        inferenceSuccessCounter.increment();

                        requestLog.setLatencyMs(latencyMs);
                        requestLog.setStatusCode(200);
                        requestLog.setStatus("completed");
                        requestLog.setResponseBody(result);
                        requestLog.setCompletedAt(LocalDateTime.now());
                        saveRequestLog(requestLog).subscribe();

                        Map<String, Object> response = new HashMap<>();
                        response.put("request_id", requestId);
                        response.put("provider_id", requestLog.getProviderId());
                        response.put("latency_ms", latencyMs);
                        response.put("fallback_from", requestLog.getFallbackFrom());
                        response.put("fallback_to", requestLog.getFallbackTo());
                        response.put("retry_count", requestLog.getRetryCount());
                        response.put("result", result);
                        return Mono.just(response);
                    });
        }).onErrorResume(e -> {
            log.error("Inference request {} failed: {}", requestId, e.getMessage());
            inferenceFailureCounter.increment();
            sample.stop(inferenceLatencyTimer);

            requestLog.setStatus("failed");
            requestLog.setErrorMessage(e.getMessage());
            requestLog.setCompletedAt(LocalDateTime.now());
            saveRequestLog(requestLog).subscribe();

            return Mono.error(e);
        });
    }

    private Mono<Map<String, Object>> callWithFallback(InferenceCallDTO dto, ModelRoute route,
                                                        List<String> providerIds, int attempt,
                                                        String failedProvider, InferenceRequest requestLog) {
        if (attempt >= providerIds.size()) {
            if (Boolean.TRUE.equals(route.getEnableFallback()) && Boolean.TRUE.equals(dto.getEnableFallback())
                    && route.getFallbackProviders() != null && !route.getFallbackProviders().isEmpty()) {
                log.info("All primary providers failed, attempting fallback providers for route: {}", route.getRouteId());
                inferenceFallbackCounter.increment();
                return callWithFallback(dto, route, route.getFallbackProviders(), 0, failedProvider, requestLog);
            }
            return Mono.error(new BusinessException("All providers failed for route: " + route.getRouteId()));
        }

        return selectProviderByStrategy(providerIds, route.getLoadBalanceStrategy())
                .flatMap(providerId -> getProvider(providerId))
                .flatMap(provider -> {
                    if (!isCircuitBreakerClosed(provider.getProviderId())) {
                        log.warn("Circuit breaker open for provider {}, trying next", provider.getProviderId());
                        return callWithFallback(dto, route, providerIds, attempt + 1, provider.getProviderId(), requestLog);
                    }

                    requestLog.setProviderId(provider.getProviderId());
                    if (failedProvider != null) {
                        requestLog.setFallbackFrom(failedProvider);
                        requestLog.setFallbackTo(provider.getProviderId());
                    }

                    int timeoutMs = dto.getTimeoutMs() != null ? dto.getTimeoutMs() :
                            (route.getTimeoutMs() != null ? route.getTimeoutMs() : 30000);
                    int maxRetries = route.getMaxRetries() != null ? route.getMaxRetries() : 2;

                    return callProviderWithRetry(provider, dto.getRequestBody(), timeoutMs, maxRetries, 0, requestLog)
                            .doOnNext(result -> updateProviderStats(provider, true, 0))
                            .onErrorResume(e -> {
                                log.warn("Provider {} failed: {}", provider.getProviderId(), e.getMessage());
                                updateProviderStats(provider, false, 0).subscribe();
                                recordFailure(provider.getProviderId(), route.getFailureThreshold(), route.getCircuitBreakerOpenMs());
                                return callWithFallback(dto, route, providerIds, attempt + 1, provider.getProviderId(), requestLog);
                            });
                });
    }

    private Mono<Map<String, Object>> callProviderWithRetry(ModelProvider provider, Map<String, Object> requestBody,
                                                             int timeoutMs, int maxRetries, int retryCount,
                                                             InferenceRequest requestLog) {
        return callProvider(provider, requestBody, timeoutMs)
                .doOnNext(result -> requestLog.setRetryCount(retryCount))
                .retryWhen(Retry.max(maxRetries)
                        .filter(e -> !(e instanceof TimeoutException))
                        .doBeforeRetry(s -> log.warn("Retrying provider {} (attempt {}/{}): {}",
                                provider.getProviderId(), retryCount + 1, maxRetries, s.failure().getMessage())));
    }

    @Override
    public Mono<Map<String, Object>> callProvider(ModelProvider provider, Map<String, Object> requestBody, int timeoutMs) {
        providerRequestCounts.computeIfAbsent(provider.getProviderId(), k -> new AtomicLong(0)).incrementAndGet();

        String inferenceUrl = provider.getBaseUrl() + "/v1/chat/completions";
        if (provider.getConfig() != null && provider.getConfig().containsKey("inference_path")) {
            inferenceUrl = provider.getBaseUrl() + provider.getConfig().get("inference_path");
        }

        final String finalInferenceUrl = inferenceUrl;
        return Mono.defer(() -> {
            long start = System.currentTimeMillis();
            return webClientBuilder.build().post().uri(finalInferenceUrl)
                    .header("Authorization", "Bearer " + provider.getApiKey())
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, response ->
                            response.bodyToMono(String.class)
                                    .flatMap(body -> Mono.error(new BusinessException(
                                            String.format("Provider %s returned %d: %s",
                                                    provider.getProviderId(), response.statusCode().value(), body)))))
                    .bodyToMono(Map.class)
                    .cast(Map.class)
                    .map(response -> {
                        long latency = System.currentTimeMillis() - start;
                        updateProviderStats(provider, true, latency).subscribe();
                        return response;
                    })
                    .timeout(Duration.ofMillis(timeoutMs))
                    .onErrorMap(java.util.concurrent.TimeoutException.class,
                            e -> new TimeoutException("Provider " + provider.getProviderId() + " timed out after " + timeoutMs + "ms"));
        });
    }

    @Override
    public Mono<String> selectProviderByStrategy(List<String> providerIds, String strategy) {
        if (providerIds == null || providerIds.isEmpty()) {
            return Mono.error(new BusinessException("No providers available"));
        }

        return switch (strategy != null ? strategy : "round_robin") {
            case "round_robin" -> Mono.just(selectRoundRobin(providerIds));
            case "weighted_round_robin" -> selectWeightedRoundRobin(providerIds);
            case "random" -> Mono.just(selectRandom(providerIds));
            case "least_requests" -> selectLeastRequests(providerIds);
            case "priority" -> selectByPriority(providerIds);
            default -> Mono.just(selectRoundRobin(providerIds));
        };
    }

    private String selectRoundRobin(List<String> providerIds) {
        String key = String.join(",", providerIds);
        AtomicInteger counter = roundRobinCounters.computeIfAbsent(key, k -> new AtomicInteger(0));
        int index = counter.getAndIncrement() % providerIds.size();
        return providerIds.get(Math.abs(index));
    }

    private Mono<String> selectWeightedRoundRobin(List<String> providerIds) {
        return Flux.fromIterable(providerIds)
                .flatMap(this::getProvider)
                .collectList()
                .map(providers -> {
                    int totalWeight = providers.stream().mapToInt(p -> p.getWeight() != null ? p.getWeight() : 100).sum();
                    int random = RandomUtil.randomInt(0, totalWeight);
                    int current = 0;
                    for (ModelProvider provider : providers) {
                        current += provider.getWeight() != null ? provider.getWeight() : 100;
                        if (random < current) {
                            return provider.getProviderId();
                        }
                    }
                    return providers.get(0).getProviderId();
                });
    }

    private String selectRandom(List<String> providerIds) {
        return providerIds.get(RandomUtil.randomInt(0, providerIds.size()));
    }

    private Mono<String> selectLeastRequests(List<String> providerIds) {
        return Flux.fromIterable(providerIds)
                .collectMap(id -> id, id -> providerRequestCounts.getOrDefault(id, new AtomicLong(0)).get())
                .map(counts -> counts.entrySet().stream()
                        .min(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse(providerIds.get(0)));
    }

    private Mono<String> selectByPriority(List<String> providerIds) {
        return Flux.fromIterable(providerIds)
                .flatMap(this::getProvider)
                .sort((a, b) -> Integer.compare(
                        b.getPriority() != null ? b.getPriority() : 0,
                        a.getPriority() != null ? a.getPriority() : 0))
                .next()
                .map(ModelProvider::getProviderId);
    }

    private boolean isCircuitBreakerClosed(String providerId) {
        String key = CIRCUIT_BREAKER_PREFIX + providerId;
        return Boolean.FALSE.equals(Boolean.TRUE.equals(Boolean.parseBoolean(
                Objects.requireNonNull(redisTemplate.hasKey(key).block()))));
    }

    private void recordFailure(String providerId, double threshold, int openMs) {
        String failureKey = "failures:" + providerId;
        String totalKey = "total:" + providerId;

        redisTemplate.opsForValue().increment(failureKey).subscribe();
        redisTemplate.opsForValue().increment(totalKey).subscribe();

        redisTemplate.opsForValue().get(totalKey)
                .map(Long::parseLong)
                .filter(total -> total >= 10)
                .flatMap(total -> redisTemplate.opsForValue().get(failureKey)
                        .map(failures -> Long.parseLong(failures) / (double) total)
                        .filter(rate -> rate >= threshold)
                        .flatMap(rate -> {
                            log.warn("Provider {} failure rate {:.2f} exceeds threshold {:.2f}, opening circuit breaker",
                                    providerId, rate, threshold);
                            return redisTemplate.opsForValue().set(CIRCUIT_BREAKER_PREFIX + providerId,
                                    "open", Duration.ofMillis(openMs));
                        }))
                .subscribe();
    }

    private Mono<Void> updateProviderStats(ModelProvider provider, boolean success, long latencyMs) {
        return Mono.fromCallable(() -> {
            double alpha = 0.1;
            if (provider.getAvgLatencyMs() == null || provider.getAvgLatencyMs() == 0) {
                provider.setAvgLatencyMs((double) latencyMs);
            } else {
                provider.setAvgLatencyMs(provider.getAvgLatencyMs() * (1 - alpha) + latencyMs * alpha);
            }

            if (provider.getSuccessRate() == null) {
                provider.setSuccessRate(success ? 1.0 : 0.0);
            } else {
                provider.setSuccessRate(provider.getSuccessRate() * (1 - alpha) + (success ? 1.0 : 0.0) * alpha);
            }

            modelProviderMapper.updateById(provider);
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<InferenceRequest> getRequestLog(String requestId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<InferenceRequest> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(InferenceRequest::getRequestId, requestId);
            InferenceRequest request = inferenceRequestMapper.selectOne(wrapper);
            if (request == null) {
                throw new ResourceNotFoundException("Request log not found: " + requestId);
            }
            return request;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Page<InferenceRequest>> listRequestLogs(int page, int size, String modelName, String providerId,
                                                        String status, LocalDateTime startTime, LocalDateTime endTime) {
        return Mono.fromCallable(() -> {
            Page<InferenceRequest> pageParam = new Page<>(page, size);
            LambdaQueryWrapper<InferenceRequest> wrapper = new LambdaQueryWrapper<>();
            if (modelName != null) wrapper.eq(InferenceRequest::getModelName, modelName);
            if (providerId != null) wrapper.eq(InferenceRequest::getProviderId, providerId);
            if (status != null) wrapper.eq(InferenceRequest::getStatus, status);
            if (startTime != null) wrapper.ge(InferenceRequest::getStartedAt, startTime);
            if (endTime != null) wrapper.le(InferenceRequest::getStartedAt, endTime);
            wrapper.orderByDesc(InferenceRequest::getStartedAt);
            return inferenceRequestMapper.selectPage(pageParam, wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> saveRequestLog(InferenceRequest request) {
        return Mono.fromCallable(() -> {
            inferenceRequestMapper.insert(request);
            return null;
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    @Scheduled(fixedRate = 30000)
    public Flux<ModelProvider> healthCheckAllProviders() {
        log.info("Starting scheduled health check for all providers");
        return getHealthyProviders()
                .flatMapMany(Flux::fromIterable)
                .flatMap(provider -> checkProviderHealth(provider.getProviderId())
                        .onErrorResume(e -> {
                            log.warn("Health check error for provider {}: {}", provider.getProviderId(), e.getMessage());
                            return Mono.empty();
                        }));
    }

    @Override
    public Mono<Map<String, Object>> getRouteStats(String routeId) {
        return getRoute(routeId)
                .flatMap(route -> Mono.fromCallable(() -> {
                    LocalDateTime startTime = LocalDateTime.now().minusHours(24);
                    LambdaQueryWrapper<InferenceRequest> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(InferenceRequest::getRouteId, routeId)
                            .ge(InferenceRequest::getStartedAt, startTime);
                    List<InferenceRequest> requests = inferenceRequestMapper.selectList(wrapper);

                    long totalRequests = requests.size();
                    long successRequests = requests.stream().filter(r -> "completed".equals(r.getStatus())).count();
                    long failedRequests = requests.stream().filter(r -> "failed".equals(r.getStatus())).count();
                    long fallbackRequests = requests.stream().filter(r -> r.getFallbackFrom() != null).count();
                    double avgLatency = requests.stream()
                            .filter(r -> r.getLatencyMs() != null)
                            .mapToLong(InferenceRequest::getLatencyMs)
                            .average()
                            .orElse(0.0);

                    Map<String, Object> stats = new HashMap<>();
                    stats.put("route_id", routeId);
                    stats.put("total_requests_24h", totalRequests);
                    stats.put("success_requests_24h", successRequests);
                    stats.put("failed_requests_24h", failedRequests);
                    stats.put("fallback_requests_24h", fallbackRequests);
                    stats.put("success_rate_24h", totalRequests > 0 ? (double) successRequests / totalRequests : 0);
                    stats.put("avg_latency_ms_24h", avgLatency);
                    stats.put("primary_providers", route.getPrimaryProviders());
                    stats.put("fallback_providers", route.getFallbackProviders());
                    stats.put("load_balance_strategy", route.getLoadBalanceStrategy());

                    return stats;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BusinessException("Failed to serialize object", e);
        }
    }

    private <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            return null;
        }
    }
}
