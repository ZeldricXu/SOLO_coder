package com.taskplatform.controller;

import com.taskplatform.common.enums.StageType;
import com.taskplatform.common.response.ApiResponse;
import com.taskplatform.modelregistry.ModelRegistryService;
import com.taskplatform.persistence.entity.ModelEntity;
import com.taskplatform.persistence.entity.ModelVersion;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelRegistryService modelRegistryService;

    @PostMapping
    public ApiResponse<ModelEntity> createModel(@RequestBody Map<String, Object> request) {
        ModelEntity model = new ModelEntity();
        model.setName((String) request.get("name"));
        model.setDescription((String) request.get("description"));
        model.setModelType((String) request.get("modelType"));
        model.setFramework((String) request.get("framework"));
        String createdBy = (String) request.getOrDefault("createdBy", "system");

        return ApiResponse.created(modelRegistryService.createModel(model, createdBy));
    }

    @GetMapping("/{modelId}")
    public ApiResponse<ModelEntity> getModel(@PathVariable String modelId) {
        return ApiResponse.success(modelRegistryService.getModel(modelId));
    }

    @GetMapping
    public ApiResponse<List<ModelEntity>> listModels(
            @RequestParam(required = false) String modelType,
            @RequestParam(required = false) String stage) {
        StageType stageType = stage != null ? StageType.valueOf(stage.toUpperCase()) : null;
        return ApiResponse.success(modelRegistryService.listModels(modelType, stageType));
    }

    @PostMapping("/{modelId}/versions")
    public ApiResponse<ModelVersion> createVersion(
            @PathVariable String modelId,
            @RequestBody Map<String, Object> request) {
        ModelVersion version = new ModelVersion();
        version.setArtifactPath((String) request.get("artifactPath"));
        version.setChecksum((String) request.get("checksum"));
        version.setSizeBytes(request.get("sizeBytes") != null ?
                ((Number) request.get("sizeBytes")).longValue() : null);
        version.setDescription((String) request.get("description"));
        String createdBy = (String) request.getOrDefault("createdBy", "system");

        return ApiResponse.created(modelRegistryService.createVersion(modelId, version, createdBy));
    }

    @GetMapping("/{modelId}/versions")
    public ApiResponse<List<ModelVersion>> listVersions(@PathVariable String modelId) {
        return ApiResponse.success(modelRegistryService.listVersions(modelId));
    }

    @PostMapping("/{modelId}/versions/{version}/promote")
    public ApiResponse<ModelVersion> promoteVersion(
            @PathVariable String modelId,
            @PathVariable String version,
            @RequestBody Map<String, Object> request) {
        StageType targetStage = StageType.valueOf(((String) request.get("stage")).toUpperCase());
        String promotedBy = (String) request.getOrDefault("promotedBy", "system");

        return ApiResponse.success(modelRegistryService.promoteVersion(
                modelId, version, targetStage, promotedBy));
    }

    @GetMapping("/{modelId}/metrics")
    public ApiResponse<Map<String, Object>> getModelMetrics(@PathVariable String modelId) {
        return ApiResponse.success(modelRegistryService.getModelMetrics(modelId));
    }
}
