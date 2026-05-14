package com.recruitment.controller;

import com.recruitment.common.dto.ApiResponse;
import com.recruitment.common.enums.InterviewType;
import com.recruitment.model.Workflow;
import com.recruitment.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowController {
    private final WorkflowService workflowService;

    @GetMapping("/position-type/{positionType}")
    public ResponseEntity<ApiResponse<Workflow>> getWorkflowByPositionType(
            @PathVariable String positionType) {
        log.info("API: 获取职位类型工作流, type: {}", positionType);
        Workflow workflow = workflowService.getWorkflowByPositionType(positionType);
        return ResponseEntity.ok(ApiResponse.success(workflow));
    }

    @GetMapping("/position-type/{positionType}/stages")
    public ResponseEntity<ApiResponse<List<InterviewType>>> getInterviewStages(
            @PathVariable String positionType) {
        log.info("API: 获取职位类型面试阶段, type: {}", positionType);
        List<InterviewType> stages = workflowService.getInterviewStages(positionType);
        return ResponseEntity.ok(ApiResponse.success(stages));
    }

    @GetMapping("/position-type/{positionType}/stages/total")
    public ResponseEntity<ApiResponse<Integer>> getTotalInterviewStages(
            @PathVariable String positionType) {
        log.info("API: 获取职位类型总面试阶段数, type: {}", positionType);
        int total = workflowService.getTotalInterviewStages(positionType);
        return ResponseEntity.ok(ApiResponse.success(total));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Workflow>>> getAllWorkflows() {
        List<Workflow> workflows = workflowService.getAllWorkflows();
        return ResponseEntity.ok(ApiResponse.success(workflows));
    }

    @GetMapping("/{workflowId}")
    public ResponseEntity<ApiResponse<Workflow>> getWorkflowById(@PathVariable String workflowId) {
        Workflow workflow = workflowService.getWorkflowById(workflowId);
        return ResponseEntity.ok(ApiResponse.success(workflow));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Workflow>> createWorkflow(
            @RequestParam String name,
            @RequestParam String positionType,
            @RequestParam List<InterviewType> stages,
            @RequestParam(required = false) String screenRules,
            @RequestParam(defaultValue = "false") boolean isDefault) {
        log.info("API: 创建工作流, name: {}", name);
        Workflow workflow = workflowService.createWorkflow(name, positionType, stages, screenRules, isDefault);
        return ResponseEntity.ok(ApiResponse.success("工作流创建成功", workflow));
    }

    @PostMapping("/evaluate")
    public ResponseEntity<ApiResponse<Boolean>> evaluateScreenRules(
            @RequestParam(required = false) String candidateEducation,
            @RequestParam(required = false) String candidateExperience,
            @RequestParam(required = false) String positionRequirement) {
        boolean result = workflowService.evaluateScreenRules(candidateEducation, candidateExperience, positionRequirement);
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
