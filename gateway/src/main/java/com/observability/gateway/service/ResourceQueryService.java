package com.observability.gateway.service;

import com.observability.common.dto.ResourceStatusResponse;
import com.observability.common.entity.ResourceEntity;
import reactor.core.publisher.Mono;

import java.util.Optional;

public interface ResourceQueryService {

    Mono<ResourceStatusResponse> getResourceStatus(String id);

    Mono<Optional<ResourceEntity>> findById(String id);

    Mono<Boolean> exists(String id);
}
