package com.metricplatform.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.metricplatform.common.ApiResponse;
import com.metricplatform.entity.SysEntity;
import com.metricplatform.entity.SysRunInstance;
import com.metricplatform.service.ResourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceService resourceService;

    @PostMapping
    public Mono<ApiResponse<Map<String, Object>>> createResource(@RequestBody Map<String, Object> request) {
        String type = (String) request.get("type");
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) request.get("config");
        @SuppressWarnings("unchecked")
        Map<String, Object> labels = (Map<String, Object>) request.get("labels");
        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) request.get("attributes");

        SysEntity entity = resourceService.createResource(type, config, labels, attributes);

        Map<String, Object> result = new HashMap<>();
        result.put("id", entity.getId());
        result.put("status", entity.getStatus());
        result.put("type", entity.getType());
        result.put("createdAt", entity.getCreatedAt());

        return Mono.just(ApiResponse.created(result));
    }

    @GetMapping("/{id}/status")
    public Mono<ApiResponse<Map<String, Object>>> getResourceStatus(@PathVariable String id) {
        Map<String, Object> status = resourceService.getResourceStatus(id);
        if (status.isEmpty()) {
            return Mono.just(ApiResponse.notFound("资源不存在"));
        }
        return Mono.just(ApiResponse.success(status));
    }

    @GetMapping
    public Mono<ApiResponse<Page<SysEntity>>> listResources(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Map<String, Object> labels,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SysEntity> result = resourceService.listResources(type, status, labels, page, size);
        return Mono.just(ApiResponse.success(result));
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<SysEntity>> getResource(@PathVariable String id) {
        SysEntity entity = resourceService.getById(id);
        if (entity != null) {
            return Mono.just(ApiResponse.success(entity));
        } else {
            return Mono.just(ApiResponse.notFound("资源不存在"));
        }
    }

    @PutMapping("/{id}")
    public Mono<ApiResponse<SysEntity>> updateResource(@PathVariable String id,
                                                        @RequestBody Map<String, Object> request) {
        SysEntity entity = resourceService.getById(id);
        if (entity == null) {
            return Mono.just(ApiResponse.notFound("资源不存在"));
        }

        if (request.containsKey("config")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> config = (Map<String, Object>) request.get("config");
            entity.setConfig(config);
        }
        if (request.containsKey("labels")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> labels = (Map<String, Object>) request.get("labels");
            entity.setLabels(labels);
        }
        if (request.containsKey("attributes")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> attributes = (Map<String, Object>) request.get("attributes");
            entity.setAttributes(attributes);
        }
        if (request.containsKey("status")) {
            entity.setStatus((String) request.get("status"));
        }

        resourceService.updateById(entity);
        return Mono.just(ApiResponse.success(entity));
    }

    @DeleteMapping("/{id}")
    public Mono<ApiResponse<Void>> deleteResource(@PathVariable String id) {
        boolean result = resourceService.deleteResource(id);
        if (result) {
            return Mono.just(ApiResponse.success(null));
        } else {
            return Mono.just(ApiResponse.notFound("资源不存在"));
        }
    }

    @PostMapping("/batch")
    public Mono<ApiResponse<Map<String, Object>>> batchOperation(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> operations = (List<Map<String, Object>>) request.get("operations");

        List<ResourceService.BatchResult> results = resourceService.batchOperation(operations);

        Map<String, Object> response = new HashMap<>();
        response.put("batchId", "batch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        response.put("results", results);

        long successCount = results.stream().filter(r -> "success".equals(r.getStatus())).count();
        long failCount = results.size() - successCount;
        response.put("successCount", successCount);
        response.put("failCount", failCount);

        return Mono.just(ApiResponse.success(response));
    }

    @PostMapping("/{id}/start")
    public Mono<ApiResponse<Map<String, Object>>> startResource(@PathVariable String id) {
        try {
            resourceService.startResource(id);
            Map<String, Object> result = new HashMap<>();
            result.put("id", id);
            result.put("message", "资源已启动");
            return Mono.just(ApiResponse.success(result));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.notFound(e.getMessage()));
        }
    }

    @PostMapping("/{id}/stop")
    public Mono<ApiResponse<Map<String, Object>>> stopResource(@PathVariable String id) {
        try {
            resourceService.stopResource(id);
            Map<String, Object> result = new HashMap<>();
            result.put("id", id);
            result.put("message", "资源已停止");
            return Mono.just(ApiResponse.success(result));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.notFound(e.getMessage()));
        }
    }

    @PostMapping("/{id}/restart")
    public Mono<ApiResponse<Map<String, Object>>> restartResource(@PathVariable String id) {
        try {
            resourceService.restartResource(id);
            Map<String, Object> result = new HashMap<>();
            result.put("id", id);
            result.put("message", "资源已重启");
            return Mono.just(ApiResponse.success(result));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.notFound(e.getMessage()));
        }
    }

    @GetMapping("/{id}/runs")
    public Mono<ApiResponse<List<SysRunInstance>>> getRunHistory(
            @PathVariable String id,
            @RequestParam(defaultValue = "20") int limit) {
        List<SysRunInstance> runs = resourceService.getRunHistory(id, limit);
        return Mono.just(ApiResponse.success(runs));
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getStats() {
        Map<String, Object> stats = new HashMap<>();

        String[] statuses = {"provisioning", "running", "ready", "stopped", "failed"};
        for (String status : statuses) {
            stats.put(status + "Count", resourceService.countResources(null, status));
        }

        stats.put("totalCount", resourceService.countResources(null, null));

        return Mono.just(ApiResponse.success(stats));
    }
}
