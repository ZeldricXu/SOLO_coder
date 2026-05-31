package com.tsdbproxy.common.controller;

import cn.hutool.core.util.IdUtil;
import com.tsdbproxy.common.dto.BatchOperationRequest;
import com.tsdbproxy.common.dto.BatchOperationResponse;
import com.tsdbproxy.common.dto.ResourceCreateRequest;
import com.tsdbproxy.common.dto.ResourceStatusResponse;
import com.tsdbproxy.common.result.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {

    @PostMapping
    public Mono<Result<ResourceStatusResponse>> createResource(@RequestBody ResourceCreateRequest request) {
        return Mono.fromCallable(() -> {
            log.info("创建资源: type={}", request.getType());
            String resourceId = "rsc_" + IdUtil.getSnowflakeNextIdStr();
            ResourceStatusResponse response = new ResourceStatusResponse(resourceId, "provisioning", 0.0);
            return Result.success(response);
        });
    }

    @GetMapping("/{id}/status")
    public Mono<Result<ResourceStatusResponse>> getResourceStatus(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            log.info("查询资源状态: id={}", id);
            ResourceStatusResponse response = new ResourceStatusResponse(id, "running", 1.0);
            return Result.success(response);
        });
    }

    @PostMapping("/batch")
    public Mono<Result<BatchOperationResponse>> batchOperation(@RequestBody BatchOperationRequest request) {
        return Mono.fromCallable(() -> {
            log.info("批量操作: 操作数量={}", request.getOperations().size());
            String batchId = "batch_" + IdUtil.getSnowflakeNextIdStr();

            List<BatchOperationResponse.OperationResult> results = new ArrayList<>();
            for (BatchOperationRequest.Operation op : request.getOperations()) {
                BatchOperationResponse.OperationResult result = new BatchOperationResponse.OperationResult(
                        op.getId(), op.getAction(), "success", null);
                results.add(result);
            }

            BatchOperationResponse response = new BatchOperationResponse(batchId, results);
            return Result.success(response);
        });
    }
}
