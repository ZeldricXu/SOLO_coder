package com.taskflow.flow.controller;

import com.taskflow.common.model.Result;
import com.taskflow.flow.model.*;
import com.taskflow.flow.service.FlowDesignerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/flows")
@RequiredArgsConstructor
public class FlowController {

    private final FlowDesignerService flowService;

    @PostMapping
    public Mono<Result<FlowDefinition>> createFlow(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestBody FlowDefinition flow) {
        return Mono.fromCallable(() -> Result.success(flowService.createFlow(tenantId, flow)));
    }

    @GetMapping
    public Mono<Result<List<FlowDefinition>>> listFlows(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId) {
        return Mono.fromCallable(() -> Result.success(flowService.listFlows(tenantId)));
    }

    @GetMapping("/{flowId}")
    public Mono<Result<FlowDefinition>> getFlow(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String flowId) {
        return Mono.fromCallable(() -> Result.success(flowService.getFlow(tenantId, flowId)));
    }

    @PutMapping("/{flowId}")
    public Mono<Result<FlowDefinition>> updateFlow(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String flowId,
            @RequestBody FlowDefinition flow) {
        return Mono.fromCallable(() -> Result.success(flowService.updateFlow(tenantId, flowId, flow)));
    }

    @DeleteMapping("/{flowId}")
    public Mono<Result<Void>> deleteFlow(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String flowId) {
        return Mono.fromCallable(() -> {
            flowService.deleteFlow(tenantId, flowId);
            return Result.success(null);
        });
    }

    @PostMapping("/{flowId}/validate")
    public Mono<Result<ValidationResult>> validateFlow(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String flowId) {
        return Mono.fromCallable(() -> Result.success(flowService.validateFlow(tenantId, flowId)));
    }

    @PostMapping("/{flowId}/publish")
    public Mono<Result<FlowDefinition>> publishFlow(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String flowId) {
        return Mono.fromCallable(() -> Result.success(flowService.publishFlow(tenantId, flowId)));
    }

    @PostMapping("/{flowId}/start")
    public Mono<Result<FlowInstance>> startFlow(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String flowId,
            @RequestBody(required = false) Map<String, Object> variables) {
        return Mono.fromCallable(() -> Result.success(flowService.startFlow(tenantId, flowId, variables)));
    }

    @GetMapping("/instances")
    public Mono<Result<List<FlowInstance>>> listInstances(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @RequestParam(required = false) String flowId) {
        return Mono.fromCallable(() -> Result.success(flowService.listFlowInstances(tenantId, flowId)));
    }

    @GetMapping("/instances/{instanceId}")
    public Mono<Result<FlowInstance>> getInstance(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String instanceId) {
        return Mono.fromCallable(() -> Result.success(flowService.getFlowInstance(tenantId, instanceId)));
    }

    @PostMapping("/{flowId}/nodes")
    public Mono<Result<FlowNode>> addNode(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String flowId,
            @RequestBody FlowNode node) {
        return Mono.fromCallable(() -> Result.success(flowService.addNode(tenantId, flowId, node)));
    }

    @PutMapping("/{flowId}/nodes/{nodeId}")
    public Mono<Result<FlowNode>> updateNode(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String flowId,
            @PathVariable String nodeId,
            @RequestBody FlowNode node) {
        return Mono.fromCallable(() -> Result.success(flowService.updateNode(tenantId, flowId, nodeId, node)));
    }

    @DeleteMapping("/{flowId}/nodes/{nodeId}")
    public Mono<Result<Void>> removeNode(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String flowId,
            @PathVariable String nodeId) {
        return Mono.fromCallable(() -> {
            flowService.removeNode(tenantId, flowId, nodeId);
            return Result.success(null);
        });
    }

    @GetMapping("/node-types")
    public Mono<Result<List<Map<String, Object>>>> getNodeTypes() {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> types = java.util.Arrays.stream(NodeType.values())
                    .map(type -> Map.<String, Object>of(
                            "code", type.getCode(),
                            "name", type.getName(),
                            "hasInput", type.isHasInput(),
                            "hasOutput", type.isHasOutput()
                    ))
                    .toList();
            return Result.success(types);
        });
    }
}
