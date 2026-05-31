package com.taskflow.core.resource.internal;

import com.taskflow.common.model.PageResult;
import com.taskflow.common.utils.IdGenerator;
import com.taskflow.core.resource.api.ResourceService;
import com.taskflow.core.resource.domain.Resource;
import com.taskflow.data.entity.ResourceEntity;
import com.taskflow.data.service.ResourceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 默认资源服务实现
 * 内部实现，不对外暴露
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultResourceService implements ResourceService {

    private final com.taskflow.data.service.ResourceService resourceDataService;

    @Override
    public Mono<Resource> create(Resource resource) {
        return Mono.fromCallable(() -> {
            ResourceEntity entity = new ResourceEntity();
            entity.setTenantId(resource.getTenantId());
            entity.setResourceId(resource.getResourceId() != null ? resource.getResourceId() : IdGenerator.generateId("rsc"));
            entity.setType(resource.getType());
            entity.setName(resource.getName());
            entity.setDescription(resource.getDescription());
            entity.setStatus(resource.getStatus() != null ? resource.getStatus() : "provisioning");
            entity.setAttributesMap(resource.getAttributes());
            entity.setLabelsMap(resource.getLabels());
            entity.setConfigId(resource.getConfigId());

            ResourceEntity saved = resourceDataService.create(entity);
            log.info("Resource created: {} - {}", saved.getResourceId(), saved.getName());
            return toDomain(saved);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Resource> getById(String tenantId, String resourceId) {
        return Mono.fromCallable(() -> {
            ResourceEntity entity = resourceDataService.getById(tenantId, resourceId);
            return toDomain(entity);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Resource> update(Resource resource) {
        return Mono.fromCallable(() -> {
            ResourceEntity entity = resourceDataService.getById(resource.getTenantId(), resource.getResourceId());
            if (resource.getName() != null) {
                entity.setName(resource.getName());
            }
            if (resource.getDescription() != null) {
                entity.setDescription(resource.getDescription());
            }
            if (resource.getAttributes() != null) {
                entity.setAttributesMap(resource.getAttributes());
            }
            if (resource.getLabels() != null) {
                entity.setLabelsMap(resource.getLabels());
            }
            ResourceEntity updated = resourceDataService.update(entity);
            return toDomain(updated);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> delete(String tenantId, String resourceId) {
        return Mono.fromRunnable(() -> {
            resourceDataService.delete(tenantId, resourceId);
            log.info("Resource deleted: {}", resourceId);
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<PageResult<Resource>> list(String tenantId, String type, int page, int size) {
        return Mono.fromCallable(() -> {
            PageResult<ResourceEntity> entityPage = resourceDataService.list(tenantId, type, page, size);
            return PageResult.of(
                    entityPage.getItems().stream().map(this::toDomain).toList(),
                    entityPage.getTotal(),
                    entityPage.getPage(),
                    entityPage.getSize()
            );
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Resource> updateStatus(String tenantId, String resourceId, String status) {
        return Mono.fromCallable(() -> {
            ResourceEntity entity = resourceDataService.getById(tenantId, resourceId);
            entity.setStatus(status);
            ResourceEntity updated = resourceDataService.update(entity);
            return toDomain(updated);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Resource toDomain(ResourceEntity entity) {
        return Resource.builder()
                .resourceId(entity.getResourceId())
                .tenantId(entity.getTenantId())
                .type(entity.getType())
                .name(entity.getName())
                .description(entity.getDescription())
                .status(entity.getStatus())
                .attributes(entity.getAttributesMap())
                .labels(entity.getLabelsMap())
                .configId(entity.getConfigId())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
