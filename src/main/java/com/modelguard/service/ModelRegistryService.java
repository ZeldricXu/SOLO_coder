package com.modelguard.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.dto.ModelRegistrationDTO;
import com.modelguard.dto.ModelVersionCreateDTO;
import com.modelguard.dto.StageTransitionDTO;
import com.modelguard.entity.ModelRegistration;
import com.modelguard.entity.ModelVersion;
import com.modelguard.entity.StageTransition;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface ModelRegistryService {

    Mono<ModelRegistration> registerModel(ModelRegistrationDTO dto);

    Mono<ModelRegistration> updateModel(String modelId, ModelRegistrationDTO dto);

    Mono<Void> deleteModel(String modelId);

    Mono<ModelRegistration> getModel(String modelId);

    Mono<Page<ModelRegistration>> listModels(int page, int size, String modelType, String owner, String stage, String status);

    Mono<ModelVersion> createVersion(ModelVersionCreateDTO dto);

    Mono<ModelVersion> getVersion(String modelId, String version);

    Mono<List<ModelVersion>> listVersions(String modelId);

    Mono<Page<ModelVersion>> listVersionsPaged(String modelId, int page, int size, String stage);

    Mono<ModelVersion> getLatestVersion(String modelId);

    Mono<ModelVersion> getVersionByStage(String modelId, String stage);

    Mono<StageTransition> transitionStage(StageTransitionDTO dto);

    Mono<StageTransition> rollbackTransition(String transitionId, String reason, String rolledBackBy);

    Mono<List<StageTransition>> getTransitionHistory(String modelId, String version);

    Mono<ModelVersion> approveVersion(String modelId, String version, String approvedBy, String notes);

    Mono<ModelVersion> archiveVersion(String modelId, String version);

    Mono<Map<String, Object>> getModelSummary(String modelId);

    Flux<ModelVersion> promoteVersionsScheduled();

    Mono<Boolean> validateStageTransition(String fromStage, String toStage, String modelId, String version);

    Mono<Map<String, Object>> compareVersions(String modelId, String version1, String version2);
}
