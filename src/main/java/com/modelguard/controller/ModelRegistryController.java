package com.modelguard.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.ApiResponse;
import com.modelguard.common.PageResult;
import com.modelguard.dto.ModelRegistrationDTO;
import com.modelguard.dto.ModelVersionCreateDTO;
import com.modelguard.dto.StageTransitionDTO;
import com.modelguard.entity.ModelRegistration;
import com.modelguard.entity.ModelVersion;
import com.modelguard.entity.StageTransition;
import com.modelguard.service.ModelRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/model-registry")
@RequiredArgsConstructor
public class ModelRegistryController {

    private final ModelRegistryService modelRegistryService;

    @PostMapping("/models")
    public Mono<ApiResponse<ModelRegistration>> registerModel(@RequestBody ModelRegistrationDTO dto) {
        return modelRegistryService.registerModel(dto)
                .map(ApiResponse::success);
    }

    @PutMapping("/models/{modelId}")
    public Mono<ApiResponse<ModelRegistration>> updateModel(
            @PathVariable String modelId,
            @RequestBody ModelRegistrationDTO dto) {
        return modelRegistryService.updateModel(modelId, dto)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/models/{modelId}")
    public Mono<ApiResponse<Void>> deleteModel(@PathVariable String modelId) {
        return modelRegistryService.deleteModel(modelId)
                .then(Mono.just(ApiResponse.success()));
    }

    @GetMapping("/models/{modelId}")
    public Mono<ApiResponse<ModelRegistration>> getModel(@PathVariable String modelId) {
        return modelRegistryService.getModel(modelId)
                .map(ApiResponse::success);
    }

    @GetMapping("/models")
    public Mono<ApiResponse<PageResult<ModelRegistration>>> listModels(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String modelType,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false) String stage,
            @RequestParam(required = false) String status) {
        return modelRegistryService.listModels(page, size, modelType, owner, stage, status)
                .map(this::toPageResponse);
    }

    @GetMapping("/models/{modelId}/summary")
    public Mono<ApiResponse<Map<String, Object>>> getModelSummary(@PathVariable String modelId) {
        return modelRegistryService.getModelSummary(modelId)
                .map(ApiResponse::success);
    }

    @PostMapping("/models/{modelId}/versions")
    public Mono<ApiResponse<ModelVersion>> createVersion(
            @PathVariable String modelId,
            @RequestBody ModelVersionCreateDTO dto) {
        dto.setModelId(modelId);
        return modelRegistryService.createVersion(dto)
                .map(ApiResponse::success);
    }

    @GetMapping("/models/{modelId}/versions/{version}")
    public Mono<ApiResponse<ModelVersion>> getVersion(
            @PathVariable String modelId,
            @PathVariable String version) {
        return modelRegistryService.getVersion(modelId, version)
                .map(ApiResponse::success);
    }

    @GetMapping("/models/{modelId}/versions")
    public Mono<ApiResponse<List<ModelVersion>>> listVersions(@PathVariable String modelId) {
        return modelRegistryService.listVersions(modelId)
                .map(ApiResponse::success);
    }

    @GetMapping("/models/{modelId}/versions/paged")
    public Mono<ApiResponse<PageResult<ModelVersion>>> listVersionsPaged(
            @PathVariable String modelId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String stage) {
        return modelRegistryService.listVersionsPaged(modelId, page, size, stage)
                .map(this::toPageResponse);
    }

    @GetMapping("/models/{modelId}/versions/latest")
    public Mono<ApiResponse<ModelVersion>> getLatestVersion(@PathVariable String modelId) {
        return modelRegistryService.getLatestVersion(modelId)
                .map(ApiResponse::success);
    }

    @GetMapping("/models/{modelId}/versions/stage/{stage}")
    public Mono<ApiResponse<ModelVersion>> getVersionByStage(
            @PathVariable String modelId,
            @PathVariable String stage) {
        return modelRegistryService.getVersionByStage(modelId, stage)
                .map(ApiResponse::success);
    }

    @PostMapping("/models/{modelId}/versions/{version}/approve")
    public Mono<ApiResponse<ModelVersion>> approveVersion(
            @PathVariable String modelId,
            @PathVariable String version,
            @RequestParam String approvedBy,
            @RequestParam(required = false) String notes) {
        return modelRegistryService.approveVersion(modelId, version, approvedBy, notes)
                .map(ApiResponse::success);
    }

    @PostMapping("/models/{modelId}/versions/{version}/archive")
    public Mono<ApiResponse<ModelVersion>> archiveVersion(
            @PathVariable String modelId,
            @PathVariable String version) {
        return modelRegistryService.archiveVersion(modelId, version)
                .map(ApiResponse::success);
    }

    @GetMapping("/models/{modelId}/versions/compare")
    public Mono<ApiResponse<Map<String, Object>>> compareVersions(
            @PathVariable String modelId,
            @RequestParam String version1,
            @RequestParam String version2) {
        return modelRegistryService.compareVersions(modelId, version1, version2)
                .map(ApiResponse::success);
    }

    @PostMapping("/transitions")
    public Mono<ApiResponse<StageTransition>> transitionStage(@RequestBody StageTransitionDTO dto) {
        return modelRegistryService.transitionStage(dto)
                .map(ApiResponse::success);
    }

    @PostMapping("/transitions/{transitionId}/rollback")
    public Mono<ApiResponse<StageTransition>> rollbackTransition(
            @PathVariable String transitionId,
            @RequestParam String reason,
            @RequestParam String rolledBackBy) {
        return modelRegistryService.rollbackTransition(transitionId, reason, rolledBackBy)
                .map(ApiResponse::success);
    }

    @GetMapping("/models/{modelId}/versions/{version}/transitions")
    public Mono<ApiResponse<List<StageTransition>>> getTransitionHistory(
            @PathVariable String modelId,
            @PathVariable String version) {
        return modelRegistryService.getTransitionHistory(modelId, version)
                .map(ApiResponse::success);
    }

    @GetMapping("/validate-transition")
    public Mono<ApiResponse<Boolean>> validateStageTransition(
            @RequestParam String fromStage,
            @RequestParam String toStage,
            @RequestParam String modelId,
            @RequestParam String version) {
        return modelRegistryService.validateStageTransition(fromStage, toStage, modelId, version)
                .map(ApiResponse::success);
    }

    @PostMapping("/auto-promote")
    public Mono<ApiResponse<Void>> triggerAutoPromote() {
        return modelRegistryService.promoteVersionsScheduled()
                .collectList()
                .then(Mono.just(ApiResponse.success()));
    }

    private <T> ApiResponse<PageResult<T>> toPageResponse(Page<T> page) {
        PageResult<T> result = new PageResult<>();
        result.setTotal(page.getTotal());
        result.setPage((int) page.getCurrent());
        result.setSize((int) page.getSize());
        result.setRecords(page.getRecords());
        return ApiResponse.success(result);
    }
}
