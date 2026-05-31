package com.taskflow.core.resource.controller;

import com.taskflow.common.model.PageResult;
import com.taskflow.common.model.Result;
import com.taskflow.core.resource.api.ResourceService;
import com.taskflow.core.resource.domain.Resource;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 资源控制器
 * 仅依赖ResourceService接口，实现依赖倒置
 */
@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceService resourceService;

    @PostMapping
    public Mono<Result<Resource>> createResource(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody Map<String, Object> request) {
        Resource resource = Resource.builder()
                .tenantId(tenantId)
                .type((String) request.get("type"))
                .name((String) request.get("name"))
                .description((String) request.get("description"))
                .attributes((Map<String, Object>) request.get("config"))
                .labels((Map<String, String>) request.get("labels"))
                .build();
        return resourceService.create(resource)
                .map(Result::success);
    }

    @GetMapping("/{id}")
    public Mono<Result<Resource>> getResource(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id) {
        return resourceService.getById(tenantId, id)
                .map(Result::success);
    }

    @GetMapping
    public Mono<Result<PageResult<Resource>>> listResources(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return resourceService.list(tenantId, type, page, size)
                .map(Result::success);
    }

    @PutMapping("/{id}")
    public Mono<Result<Resource>> updateResource(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id,
            @RequestBody Resource resource) {
        resource.setResourceId(id);
        resource.setTenantId(tenantId);
        return resourceService.update(resource)
                .map(Result::success);
    }

    @DeleteMapping("/{id}")
    public Mono<Result<Void>> deleteResource(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id) {
        return resourceService.delete(tenantId, id)
                .then(Mono.just(Result.success(null)));
    }

    @GetMapping("/{id}/status")
    public Mono<Result<Map<String, Object>>> getResourceStatus(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id) {
        return resourceService.getById(tenantId, id)
                .map(resource -> Result.success(Map.of(
                        "id", resource.getResourceId(),
                        "status", resource.getStatus(),
                        "progress", 0.8
                )));
    }

    @PatchMapping("/{id}/status")
    public Mono<Result<Resource>> updateResourceStatus(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id,
            @RequestBody Map<String, String> request) {
        return resourceService.updateStatus(tenantId, id, request.get("status"))
                .map(Result::success);
    }
}
