package com.contractai.flow.controller;

import com.contractai.common.dto.PageQuery;
import com.contractai.common.dto.PageResult;
import com.contractai.common.result.ApiResponse;
import com.contractai.flow.dto.*;
import com.contractai.flow.entity.*;
import com.contractai.flow.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @PostMapping
    public ApiResponse<WorkflowDefinition> createWorkflow(@RequestBody WorkflowCreateDTO dto) {
        return ApiResponse.created(workflowService.createWorkflow(dto));
    }

    @PostMapping("/validate")
    public ApiResponse<FlowValidationResult> validateWorkflow(@RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) request.get("nodes");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) request.get("edges");
        return ApiResponse.success(workflowService.validateWorkflow(nodes, edges));
    }

    @GetMapping
    public ApiResponse<PageResult<WorkflowDefinition>> listWorkflows(@ModelAttribute PageQuery query) {
        return ApiResponse.success(workflowService.listWorkflows(query));
    }

    @GetMapping("/{id}")
    public ApiResponse<WorkflowDefinition> getWorkflow(@PathVariable Long id) {
        return ApiResponse.success(workflowService.getWorkflow(id));
    }

    @PutMapping("/{id}/publish")
    public ApiResponse<WorkflowDefinition> publishWorkflow(@PathVariable Long id) {
        return ApiResponse.success(workflowService.publishWorkflow(id));
    }

    @GetMapping("/{id}/nodes")
    public ApiResponse<List<WorkflowNode>> getFlowNodes(@PathVariable Long id) {
        return ApiResponse.success(workflowService.getFlowNodes(id));
    }

    @GetMapping("/{id}/edges")
    public ApiResponse<List<WorkflowEdge>> getFlowEdges(@PathVariable Long id) {
        return ApiResponse.success(workflowService.getFlowEdges(id));
    }

    @PostMapping("/instances")
    public ApiResponse<WorkflowInstance> startInstance(@RequestBody InstanceStartDTO dto) {
        return ApiResponse.created(workflowService.startInstance(dto));
    }

    @GetMapping("/instances")
    public ApiResponse<PageResult<WorkflowInstance>> listInstances(@ModelAttribute PageQuery query) {
        return ApiResponse.success(workflowService.listInstances(query));
    }

    @GetMapping("/instances/{id}")
    public ApiResponse<WorkflowInstance> getInstance(@PathVariable Long id) {
        return ApiResponse.success(workflowService.getInstance(id));
    }
}
