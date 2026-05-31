package com.observability.gateway.service.impl;

import com.observability.common.dto.ResourceStatusResponse;
import com.observability.common.entity.ResourceEntity;
import com.observability.gateway.service.ResourceQueryService;
import com.observability.dal.repository.ResourceRepository;
import com.observability.dal.repository.RunInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceQueryServiceImpl implements ResourceQueryService {

    private final ResourceRepository resourceRepository;
    private final RunInstanceRepository runInstanceRepository;

    @Override
    public Mono<ResourceStatusResponse> getResourceStatus(String id) {
        return Mono.fromCallable(() -> {
            ResourceEntity resource = resourceRepository.findByResourceId(id)
                    .orElseThrow(() -> new RuntimeException("Resource not found: " + id));

            ResourceStatusResponse response = new ResourceStatusResponse();
            response.setId(id);
            response.setStatus(resource.getStatus());

            runInstanceRepository.findLatestByEntityId(id).ifPresent(runInstance -> {
                response.setProgress(runInstance.getProgress());
                response.setErrorDetail(runInstance.getErrorDetail());
            });

            return response;
        });
    }

    @Override
    public Mono<Optional<ResourceEntity>> findById(String id) {
        return Mono.fromCallable(() -> resourceRepository.findByResourceId(id));
    }

    @Override
    public Mono<Boolean> exists(String id) {
        return Mono.fromCallable(() -> resourceRepository.existsByResourceId(id));
    }
}
