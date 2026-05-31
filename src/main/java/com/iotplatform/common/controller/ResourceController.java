package com.iotplatform.common.controller;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iotplatform.common.dto.PageQuery;
import com.iotplatform.common.dto.PageResult;
import com.iotplatform.common.dto.Result;
import com.iotplatform.common.entity.SysResource;
import com.iotplatform.common.mapper.SysResourceMapper;
import com.iotplatform.common.util.IdGenerator;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final SysResourceMapper resourceMapper;

    @PostMapping
    public Mono<Result<Map<String, Object>>> createResource(@RequestBody Map<String, Object> request) {
        return Mono.fromCallable(() -> {
            SysResource resource = new SysResource();
            resource.setResourceId(IdGenerator.generateId("rsc"));
            resource.setType((String) request.get("type"));
            resource.setStatus("provisioning");
            resource.setAttributes(request.get("config") != null ? JSONUtil.toJsonStr(request.get("config")) : null);
            resource.setLabels(request.get("labels") != null ? JSONUtil.toJsonStr(request.get("labels")) : null);

            resourceMapper.insert(resource);

            Map<String, Object> result = Map.of(
                    "id", resource.getResourceId(),
                    "status", resource.getStatus()
            );
            return Result.success(201, "Created", result);
        });
    }

    @GetMapping("/{id}/status")
    public Mono<Result<Map<String, Object>>> getResourceStatus(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<SysResource> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SysResource::getResourceId, id);
            SysResource resource = resourceMapper.selectOne(wrapper);
            if (resource == null) {
                return Result.error(404, "资源不存在");
            }
            Map<String, Object> result = Map.of(
                    "id", resource.getResourceId(),
                    "status", resource.getStatus(),
                    "progress", 0.8
            );
            return Result.success(result);
        });
    }

    @PostMapping("/batch")
    public Mono<Result<Map<String, Object>>> batchOperation(@RequestBody Map<String, Object> request) {
        return Mono.fromCallable(() -> {
            String batchId = IdGenerator.generateId("batch");
            List<Map<String, Object>> operations = (List<Map<String, Object>>) request.get("operations");
            List<Map<String, Object>> results = operations.stream()
                    .map(op -> Map.of(
                            "id", op.get("id"),
                            "action", op.get("action"),
                            "success", true
                    ))
                    .toList();

            Map<String, Object> result = Map.of(
                    "batch_id", batchId,
                    "results", results
            );
            return Result.success(result);
        });
    }

    @GetMapping
    public Mono<Result<PageResult<SysResource>>> listResources(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @ModelAttribute PageQuery pageQuery) {
        return Mono.fromCallable(() -> {
            Page<SysResource> page = new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize());
            LambdaQueryWrapper<SysResource> wrapper = new LambdaQueryWrapper<>();
            if (type != null) {
                wrapper.eq(SysResource::getType, type);
            }
            if (status != null) {
                wrapper.eq(SysResource::getStatus, status);
            }
            wrapper.orderByDesc(SysResource::getCreatedAt);

            IPage<SysResource> resultPage = resourceMapper.selectPage(page, wrapper);
            PageResult<SysResource> pageResult = new PageResult<>(
                    resultPage.getRecords(),
                    resultPage.getTotal(),
                    resultPage.getPages(),
                    resultPage.getCurrent(),
                    resultPage.getSize()
            );
            return Result.success(pageResult);
        });
    }

    @Data
    public static class BatchOperationRequest {
        private List<Operation> operations;

        @Data
        public static class Operation {
            private String action;
            private String id;
        }
    }
}
