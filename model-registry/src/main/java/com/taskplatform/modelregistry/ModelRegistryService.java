package com.taskplatform.modelregistry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.taskplatform.common.enums.StageType;
import com.taskplatform.common.exception.BusinessException;
import com.taskplatform.common.util.IdGenerator;
import com.taskplatform.common.util.JsonUtil;
import com.taskplatform.persistence.entity.ModelEntity;
import com.taskplatform.persistence.entity.ModelVersion;
import com.taskplatform.persistence.mapper.ModelEntityMapper;
import com.taskplatform.persistence.mapper.ModelVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRegistryService {

    private final ModelEntityMapper modelMapper;
    private final ModelVersionMapper versionMapper;

    @Transactional
    public ModelEntity createModel(ModelEntity model, String createdBy) {
        model.setModelId(IdGenerator.generateModelId());
        model.setCreatedBy(createdBy);
        model.setStage(StageType.STAGING);
        modelMapper.insert(model);
        log.info("Created model: {} - {}", model.getModelId(), model.getName());
        return model;
    }

    public ModelEntity getModel(String modelId) {
        ModelEntity model = modelMapper.selectOne(
                new LambdaQueryWrapper<ModelEntity>().eq(ModelEntity::getModelId, modelId)
        );
        if (model == null) {
            throw new BusinessException(404, "MODEL_NOT_FOUND", "Model not found: " + modelId);
        }
        return model;
    }

    public List<ModelEntity> listModels(String modelType, StageType stage) {
        LambdaQueryWrapper<ModelEntity> query = new LambdaQueryWrapper<>();
        if (modelType != null) {
            query.eq(ModelEntity::getModelType, modelType);
        }
        if (stage != null) {
            query.eq(ModelEntity::getStage, stage);
        }
        query.orderByDesc(ModelEntity::getCreatedAt);
        return modelMapper.selectList(query);
    }

    @Transactional
    public ModelVersion createVersion(String modelId, ModelVersion version, String createdBy) {
        ModelEntity model = getModel(modelId);

        Integer latestVersionNum = versionMapper.selectObjs(
                new LambdaQueryWrapper<ModelVersion>()
                        .eq(ModelVersion::getModelId, modelId)
                        .select("MAX(CONVERT(SUBSTRING_INDEX(version, '.', 1), SIGNED)) as v")
        ).stream().findFirst().map(v -> v != null ? ((Number) v).intValue() : 0).orElse(0);

        version.setVersionId(IdGenerator.generateVersionId());
        version.setModelId(modelId);
        version.setVersion(String.valueOf(latestVersionNum + 1) + ".0.0");
        version.setStage(StageType.STAGING);
        version.setCreatedBy(createdBy);
        versionMapper.insert(version);

        model.setLatestVersion(version.getVersion());
        modelMapper.updateById(model);

        log.info("Created model version: {} v{}", modelId, version.getVersion());
        return version;
    }

    public ModelVersion getVersion(String modelId, String version) {
        ModelVersion modelVersion = versionMapper.selectOne(
                new LambdaQueryWrapper<ModelVersion>()
                        .eq(ModelVersion::getModelId, modelId)
                        .eq(ModelVersion::getVersion, version)
        );
        if (modelVersion == null) {
            throw new BusinessException(404, "VERSION_NOT_FOUND",
                    "Version not found: " + modelId + " v" + version);
        }
        return modelVersion;
    }

    public ModelVersion getLatestVersion(String modelId) {
        ModelEntity model = getModel(modelId);
        if (model.getLatestVersion() == null) {
            throw new BusinessException(404, "NO_VERSION", "No versions for model: " + modelId);
        }
        return getVersion(modelId, model.getLatestVersion());
    }

    public List<ModelVersion> listVersions(String modelId) {
        return versionMapper.selectList(
                new LambdaQueryWrapper<ModelVersion>()
                        .eq(ModelVersion::getModelId, modelId)
                        .orderByDesc(ModelVersion::getCreatedAt)
        );
    }

    @Transactional
    public ModelVersion promoteVersion(String modelId, String version, StageType targetStage, String promotedBy) {
        ModelVersion modelVersion = getVersion(modelId, version);

        if (modelVersion.getStage() == targetStage) {
            return modelVersion;
        }

        validateStageTransition(modelVersion.getStage(), targetStage);

        if (targetStage == StageType.PRODUCTION) {
            versionMapper.update(
                    null,
                    new LambdaQueryWrapper<ModelVersion>()
                            .eq(ModelVersion::getModelId, modelId)
                            .eq(ModelVersion::getStage, StageType.PRODUCTION)
                            .set(ModelVersion::getStage, StageType.STAGING)
            );
        }

        modelVersion.setStage(targetStage);
        modelVersion.setPromotedAt(LocalDateTime.now());
        versionMapper.updateById(modelVersion);

        ModelEntity model = getModel(modelId);
        if (targetStage == StageType.PRODUCTION ||
            (targetStage == StageType.STAGING && model.getStage() != StageType.PRODUCTION)) {
            model.setStage(targetStage);
            modelMapper.updateById(model);
        }

        log.info("Promoted model version: {} v{} to {}", modelId, version, targetStage);
        return modelVersion;
    }

    private void validateStageTransition(StageType current, StageType target) {
        if (current == StageType.ARCHIVED) {
            throw new BusinessException(400, "INVALID_TRANSITION",
                    "Cannot transition from ARCHIVED stage");
        }

        if (target == StageType.ARCHIVED) {
            return;
        }

        switch (current) {
            case STAGING:
                if (target != StageType.PRODUCTION) {
                    throw new BusinessException(400, "INVALID_TRANSITION",
                            "Can only transition from STAGING to PRODUCTION or ARCHIVED");
                }
                break;
            case PRODUCTION:
                if (target != StageType.STAGING && target != StageType.ARCHIVED) {
                    throw new BusinessException(400, "INVALID_TRANSITION",
                            "Can only transition from PRODUCTION to STAGING or ARCHIVED");
                }
                break;
            default:
                throw new BusinessException(400, "INVALID_TRANSITION",
                        "Invalid current stage: " + current);
        }
    }

    @Transactional
    public void archiveModel(String modelId) {
        ModelEntity model = getModel(modelId);
        model.setStage(StageType.ARCHIVED);
        model.setArchivedAt(LocalDateTime.now());
        modelMapper.updateById(model);

        versionMapper.update(
                null,
                new LambdaQueryWrapper<ModelVersion>()
                        .eq(ModelVersion::getModelId, modelId)
                        .ne(ModelVersion::getStage, StageType.ARCHIVED)
                        .set(ModelVersion::getStage, StageType.ARCHIVED)
                        .set(ModelVersion::getArchivedAt, LocalDateTime.now())
        );

        log.info("Archived model: {}", modelId);
    }

    public Map<String, Object> getModelMetrics(String modelId) {
        ModelEntity model = getModel(modelId);
        List<ModelVersion> versions = listVersions(modelId);

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("modelId", modelId);
        metrics.put("name", model.getName());
        metrics.put("stage", model.getStage());
        metrics.put("totalVersions", versions.size());
        metrics.put("latestVersion", model.getLatestVersion());

        Map<String, Integer> versionCountByStage = new HashMap<>();
        for (ModelVersion v : versions) {
            String stage = v.getStage() != null ? v.getStage().name() : "UNKNOWN";
            versionCountByStage.put(stage, versionCountByStage.getOrDefault(stage, 0) + 1);
        }
        metrics.put("versionCountByStage", versionCountByStage);

        if (!versions.isEmpty()) {
            ModelVersion latest = versions.get(0);
            if (latest.getMetrics() != null) {
                metrics.put("latestMetrics", JsonUtil.fromJson(latest.getMetrics(), Map.class));
            }
        }

        return metrics;
    }

    public ModelVersion updateVersionMetrics(String modelId, String version, Map<String, Object> metrics) {
        ModelVersion modelVersion = getVersion(modelId, version);
        modelVersion.setMetrics(JsonUtil.toJson(metrics));
        versionMapper.updateById(modelVersion);
        return modelVersion;
    }
}
