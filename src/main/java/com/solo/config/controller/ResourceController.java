package com.solo.config.controller;

import com.solo.config.common.IdGenerator;
import com.solo.config.common.Result;
import com.solo.config.dto.BatchOperationRequest;
import com.solo.config.dto.CreateResourceRequest;
import com.solo.config.entity.Resource;
import com.solo.config.module.data.DataAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final DataAccessService dataAccessService;

    @PostMapping
    public Mono<Result<Map<String, Object>>> createResource(@Valid @RequestBody CreateResourceRequest request) {
        Resource resource = new Resource();
        resource.setResourceId(IdGenerator.generateResourceId());
        resource.setType(request.getType());
        resource.setStatus("provisioning");
        resource.setConfig(request.getConfig());
        resource.setLabels(request.getLabels());
        resource.setAttributes(request.getAttributes());

        return dataAccessService.createResource(resource)
                .map(r -> {
                    Map<String, Object> data = new HashMap<>();
                    data.put("id", r.getResourceId());
                    data.put("status", r.getStatus());
                    return Result.success(data);
                });
    }

    @GetMapping("/{id}/status")
    public Mono<Result<Map<String, Object>>> getResourceStatus(@PathVariable String id) {
        return dataAccessService.getResource(id)
                .map(resource -> {
                    Map<String, Object> data = new HashMap<>();
                    data.put("id", resource.getResourceId());
                    data.put("status", resource.getStatus());
                    data.put("type", resource.getType());
                    data.put("progress", 0.8);
                    return Result.success(data);
                })
                .defaultIfEmpty(Result.error(404, "资源不存在"));
    }

    @GetMapping
    public Flux<Resource> listResources(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return dataAccessService.listResources(type, status, page, size);
    }

    @GetMapping("/{id}")
    public Mono<Resource> getResource(@PathVariable String id) {
        return dataAccessService.getResource(id);
    }

    @PutMapping("/{id}")
    public Mono<Resource> updateResource(@PathVariable String id, @RequestBody Resource resource) {
        return dataAccessService.updateResource(id, resource);
    }

    @DeleteMapping("/{id}")
    public Mono<Result<Void>> deleteResource(@PathVariable String id) {
        return dataAccessService.deleteResource(id)
                .then(Mono.just(Result.success()));
    }

    @PostMapping("/batch")
    public Mono<Result<Map<String, Object>>> batchOperation(@Valid @RequestBody BatchOperationRequest request) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (BatchOperationRequest.Operation op : request.getOperations()) {
            Map<String, Object> result = new HashMap<>();
            result.put("id", op.getId());
            result.put("action", op.getAction());
            result.put("status", "success");
            results.add(result);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("batch_id", "batch_" + IdGenerator.generate("batch").substring(6));
        data.put("results", results);

        return Mono.just(Result.success(data));
    }

    @GetMapping("/stats/datasource")
    public Mono<Map<String, Object>> getDataSourceStats() {
        return dataAccessService.getDataSourceStats();
    }
}
