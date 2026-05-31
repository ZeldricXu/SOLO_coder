package com.llmgateway.modelregistry.controller;

import com.llmgateway.common.api.R;
import com.llmgateway.common.api.PageResult;
import com.llmgateway.modelregistry.dto.ModelRegisterDTO;
import com.llmgateway.modelregistry.dto.ModelVersionCreateDTO;
import com.llmgateway.modelregistry.dto.StageTransitionDTO;
import com.llmgateway.modelregistry.entity.Model;
import com.llmgateway.modelregistry.entity.ModelVersion;
import com.llmgateway.modelregistry.entity.StageTransitionLog;
import com.llmgateway.modelregistry.service.ModelService;
import com.llmgateway.modelregistry.service.ModelVersionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/model-registry")
@RequiredArgsConstructor
public class ModelRegistryController {

    private final ModelService modelService;
    private final ModelVersionService versionService;

    @PostMapping("/models")
    public R<Model> registerModel(@Valid @RequestBody ModelRegisterDTO dto) {
        return R.created(modelService.register(dto));
    }

    @GetMapping("/models/{modelId}")
    public R<Model> getModel(@PathVariable String modelId) {
        return R.success(modelService.getById(modelId));
    }

    @GetMapping("/models")
    public R<PageResult<Model>> listModels(
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) String modelType,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return R.success(modelService.list(provider, modelType, pageNum, pageSize));
    }

    @PutMapping("/models/{modelId}")
    public R<Model> updateModel(@PathVariable String modelId, @Valid @RequestBody ModelRegisterDTO dto) {
        return R.success(modelService.update(modelId, dto));
    }

    @DeleteMapping("/models/{modelId}")
    public R<Void> deleteModel(@PathVariable String modelId) {
        modelService.delete(modelId);
        return R.success();
    }

    @PostMapping("/versions")
    public R<ModelVersion> createVersion(@Valid @RequestBody ModelVersionCreateDTO dto) {
        return R.created(versionService.createVersion(dto));
    }

    @GetMapping("/versions/{versionId}")
    public R<ModelVersion> getVersion(@PathVariable String versionId) {
        return R.success(versionService.getById(versionId));
    }

    @GetMapping("/models/{modelId}/versions")
    public R<List<ModelVersion>> listModelVersions(@PathVariable String modelId) {
        return R.success(versionService.listByModelId(modelId));
    }

    @GetMapping("/models/{modelId}/versions/paged")
    public R<PageResult<ModelVersion>> listModelVersionsPaged(
            @PathVariable String modelId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return R.success(versionService.listByModelIdPaged(modelId, pageNum, pageSize));
    }

    @GetMapping("/models/{modelId}/versions/latest")
    public R<ModelVersion> getLatestVersionByStage(
            @PathVariable String modelId,
            @RequestParam(defaultValue = "production") String stage) {
        return R.success(versionService.getLatestByStage(modelId, stage));
    }

    @PostMapping("/versions/transition")
    public R<ModelVersion> transitionStage(@Valid @RequestBody StageTransitionDTO dto) {
        return R.success(versionService.transitionStage(dto));
    }

    @GetMapping("/versions/{versionId}/transitions")
    public R<List<StageTransitionLog>> getTransitionLogs(@PathVariable String versionId) {
        return R.success(versionService.getTransitionLogs(versionId));
    }

    @GetMapping("/versions/{versionId}/transitions/paged")
    public R<PageResult<StageTransitionLog>> getTransitionLogsPaged(
            @PathVariable String versionId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        return R.success(versionService.getTransitionLogsPaged(versionId, pageNum, pageSize));
    }

    @DeleteMapping("/versions/{versionId}")
    public R<Void> deleteVersion(@PathVariable String versionId) {
        versionService.deleteVersion(versionId);
        return R.success();
    }
}
