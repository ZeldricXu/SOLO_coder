package com.cicd.server.controller;

import com.cicd.common.enums.TriggerType;
import com.cicd.server.dto.TriggerPipelineRequest;
import com.cicd.server.entity.*;
import com.cicd.server.pipeline.PipelineService;
import com.cicd.server.repository.PipelineExecutionRepository;
import com.cicd.server.repository.PipelineRepository;
import com.cicd.server.repository.PipelineTemplateRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/pipelines")
@RequiredArgsConstructor
public class PipelineController {

    private final PipelineService pipelineService;
    private final PipelineRepository pipelineRepository;
    private final PipelineTemplateRepository templateRepository;
    private final PipelineExecutionRepository executionRepository;

    @GetMapping
    public ResponseEntity<List<Pipeline>> listPipelines(@RequestParam Long projectId) {
        return ResponseEntity.ok(pipelineRepository.findByProjectId(projectId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Pipeline> getPipeline(@PathVariable Long id) {
        Pipeline pipeline = pipelineService.getPipeline(id);
        return pipeline != null ? ResponseEntity.ok(pipeline) : ResponseEntity.notFound().build();
    }

    @PostMapping
    @PreAuthorize("@rbacPermissionEvaluator.hasProjectPermission(authentication, #projectId, 'edit')")
    public ResponseEntity<Pipeline> createPipeline(
            @RequestParam Long projectId,
            @RequestBody Map<String, Object> request) {
        Pipeline pipeline = pipelineService.createPipeline(
            projectId,
            (String) request.get("name"),
            (String) request.get("description"),
            (String) request.get("yamlDefinition"),
            request.get("templateId") != null ? Long.valueOf(request.get("templateId").toString()) : null
        );
        return ResponseEntity.ok(pipeline);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@rbacPermissionEvaluator.hasProjectPermission(authentication, @pipelineRepository.findById(#id).orElseThrow().project.id, 'edit')")
    public ResponseEntity<Pipeline> updatePipeline(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {
        Pipeline pipeline = pipelineService.updatePipeline(
            id,
            (String) request.get("name"),
            (String) request.get("description"),
            (String) request.get("yamlDefinition")
        );
        return ResponseEntity.ok(pipeline);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@rbacPermissionEvaluator.hasProjectPermission(authentication, @pipelineRepository.findById(#id).orElseThrow().project.id, 'edit')")
    public ResponseEntity<Void> deletePipeline(@PathVariable Long id) {
        pipelineService.deletePipeline(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/trigger")
    @PreAuthorize("@rbacPermissionEvaluator.hasProjectPermission(authentication, @pipelineRepository.findById(#id).orElseThrow().project.id, 'trigger')")
    public ResponseEntity<PipelineExecution> triggerPipeline(
            @PathVariable Long id,
            @RequestBody(required = false) TriggerPipelineRequest request) {
        Map<String, String> params = request != null ? request.getParams() : null;
        String branch = request != null ? request.getBranch() : null;
        String triggeredBy = request != null ? request.getTriggeredBy() : "manual";

        PipelineExecution execution = pipelineService.triggerPipeline(
            id, TriggerType.MANUAL, triggeredBy, branch, params
        );
        return ResponseEntity.ok(execution);
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancelExecution(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        String reason = request != null ? request.get("reason") : "Cancelled by user";
        pipelineService.cancelExecution(id, reason);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/executions")
    public ResponseEntity<Page<PipelineExecution>> getExecutions(
            @PathVariable Long id,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(pipelineService.getExecutions(id, pageable));
    }

    @GetMapping("/executions/{executionId}")
    public ResponseEntity<PipelineExecution> getExecution(@PathVariable Long executionId) {
        PipelineExecution execution = pipelineService.getExecution(executionId);
        return execution != null ? ResponseEntity.ok(execution) : ResponseEntity.notFound().build();
    }

    @GetMapping("/templates")
    public ResponseEntity<List<PipelineTemplate>> getTemplates() {
        return ResponseEntity.ok(templateRepository.findAll());
    }

    @GetMapping("/templates/{id}")
    public ResponseEntity<PipelineTemplate> getTemplate(@PathVariable Long id) {
        return templateRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/schedule")
    @PreAuthorize("@rbacPermissionEvaluator.hasProjectPermission(authentication, @pipelineRepository.findById(#id).orElseThrow().project.id, 'edit')")
    public ResponseEntity<Void> schedulePipeline(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {
        String cron = (String) request.get("cron");
        @SuppressWarnings("unchecked")
        Map<String, String> params = (Map<String, String>) request.get("params");
        pipelineService.schedulePipeline(id, cron, params);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{id}/schedule")
    public ResponseEntity<Void> unschedulePipeline(@PathVariable Long id) {
        pipelineService.unschedulePipeline(id);
        return ResponseEntity.noContent().build();
    }
}
