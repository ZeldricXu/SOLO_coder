package com.observability.gateway.service;

import com.observability.common.dto.BatchOperationRequest;
import com.observability.common.dto.BatchOperationResponse;
import com.observability.common.dto.ResourceCreateRequest;
import reactor.core.publisher.Mono;

import java.util.Map;

public interface ResourceMutationService {

    Mono<Map<String, Object>> createResource(ResourceCreateRequest request);

    Mono<BatchOperationResponse> batchOperation(BatchOperationRequest request);

    Mono<Void> startResource(String id);

    Mono<Void> stopResource(String id);

    Mono<Void> restartResource(String id);

    Mono<Void> deleteResource(String id);
}
