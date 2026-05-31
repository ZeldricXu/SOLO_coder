package com.modelguard.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.dto.InferenceCallDTO;
import com.modelguard.dto.ModelProviderDTO;
import com.modelguard.dto.ModelRouteDTO;
import com.modelguard.entity.InferenceRequest;
import com.modelguard.entity.ModelProvider;
import com.modelguard.entity.ModelRoute;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public interface InferenceGatewayService {

    Mono<ModelProvider> registerProvider(ModelProviderDTO dto);

    Mono<ModelProvider> updateProvider(String providerId, ModelProviderDTO dto);

    Mono<Void> deleteProvider(String providerId);

    Mono<ModelProvider> getProvider(String providerId);

    Mono<Page<ModelProvider>> listProviders(int page, int size, String status, String providerType);

    Mono<List<ModelProvider>> getHealthyProviders();

    Mono<ModelProvider> checkProviderHealth(String providerId);

    Mono<ModelRoute> createRoute(ModelRouteDTO dto);

    Mono<ModelRoute> updateRoute(String routeId, ModelRouteDTO dto);

    Mono<Void> deleteRoute(String routeId);

    Mono<ModelRoute> getRoute(String routeId);

    Mono<Page<ModelRoute>> listRoutes(int page, int size, String modelName, String status);

    Mono<ModelRoute> getRouteByModel(String modelName);

    Mono<Map<String, Object>> callInference(InferenceCallDTO dto);

    Mono<InferenceRequest> getRequestLog(String requestId);

    Mono<Page<InferenceRequest>> listRequestLogs(int page, int size, String modelName, String providerId,
                                                  String status, LocalDateTime startTime, LocalDateTime endTime);

    Flux<ModelProvider> healthCheckAllProviders();

    Mono<Map<String, Object>> getRouteStats(String routeId);

    Mono<String> selectProviderByStrategy(List<String> providerIds, String strategy);

    Mono<Map<String, Object>> callProvider(ModelProvider provider, Map<String, Object> requestBody, int timeoutMs);
}
