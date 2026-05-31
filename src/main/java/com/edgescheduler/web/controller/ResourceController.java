package com.edgescheduler.web.controller;

import com.edgescheduler.common.Result;
import com.edgescheduler.common.util.IdGenerator;
import com.edgescheduler.domain.entity.ResourceEntity;
import com.edgescheduler.domain.enums.ResourceStatus;
import com.edgescheduler.domain.vo.BatchResultVO;
import com.edgescheduler.domain.vo.ResourceVO;
import com.edgescheduler.infrastructure.mapper.ResourceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final ResourceMapper resourceMapper;

    @PostMapping
    public Mono<Result<ResourceVO>> createResource(@RequestBody Map<String, Object> request) {
        String type = (String) request.getOrDefault("type", "workflow");
        Map<String, Object> config = (Map<String, Object>) request.getOrDefault("config", new HashMap<>());
        Map<String, Object> labels = (Map<String, Object>) request.getOrDefault("labels", new HashMap<>());

        ResourceEntity resource = new ResourceEntity();
        String resourceId = IdGenerator.generateResourceId();
        resource.setEntityId(resourceId);
        resource.setType(type);
        resource.setStatus(ResourceStatus.PROVISIONING);
        resource.setAttributes(config);
        resource.setNamespace("default");

        resourceMapper.insert(resource);

        ResourceVO vo = new ResourceVO();
        vo.setId(resourceId);
        vo.setStatus(resource.getStatus().name().toLowerCase());
        vo.setProgress(0.0);

        return Mono.just(Result.success(201, vo));
    }

    @GetMapping("/{id}/status")
    public Mono<Result<ResourceVO>> getResourceStatus(@PathVariable String id) {
        ResourceEntity resource = resourceMapper.selectOne(null);
        if (resource == null) {
            resource = new ResourceEntity();
            resource.setEntityId(id);
            resource.setStatus(ResourceStatus.FAILED);
            resource.setAttributes(new HashMap<>());
        }

        ResourceVO vo = new ResourceVO();
        vo.setId(id);
        vo.setStatus(resource.getStatus().name().toLowerCase());
        vo.setProgress(0.8);
        vo.setAttributes(resource.getAttributes());

        return Mono.just(Result.success(vo));
    }

    @PostMapping("/batch")
    public Mono<Result<BatchResultVO>> batchOperation(@RequestBody Map<String, Object> request) {
        List<Map<String, Object>> operations = (List<Map<String, Object>>) request.get("operations");
        String batchId = IdGenerator.generateBatchId();

        List<BatchResultVO.OperationResult> results = new ArrayList<>();
        for (Map<String, Object> op : operations) {
            String id = (String) op.get("id");
            String action = (String) op.get("action");
            results.add(new BatchResultVO.OperationResult(id, action, true, "Operation completed"));
        }

        BatchResultVO batchResult = new BatchResultVO(batchId, results);
        return Mono.just(Result.success(batchResult));
    }
}
