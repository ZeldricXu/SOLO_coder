package com.llmgateway.modelregistry.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmgateway.common.api.PageResult;
import com.llmgateway.common.constant.CommonConstants;
import com.llmgateway.common.exception.BusinessException;
import com.llmgateway.common.util.IdGenerator;
import com.llmgateway.modelregistry.dto.ModelVersionCreateDTO;
import com.llmgateway.modelregistry.dto.StageTransitionDTO;
import com.llmgateway.modelregistry.entity.ModelVersion;
import com.llmgateway.modelregistry.entity.StageTransitionLog;
import com.llmgateway.modelregistry.mapper.ModelVersionMapper;
import com.llmgateway.modelregistry.mapper.StageTransitionLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelVersionService {

    private final ModelVersionMapper versionMapper;
    private final StageTransitionLogMapper transitionLogMapper;
    private final ModelService modelService;

    @Value("${model-registry.transition-log.retention-days:30}")
    private int transitionLogRetentionDays;

    @Value("${model-registry.max-versions-per-model:100}")
    private int maxVersionsPerModel;

    @Transactional(rollbackFor = Exception.class)
    public ModelVersion createVersion(ModelVersionCreateDTO dto) {
        modelService.getById(dto.getModelId());

        long versionCount = versionMapper.selectCount(
                new LambdaQueryWrapper<ModelVersion>()
                        .eq(ModelVersion::getModelId, dto.getModelId())
                        .eq(ModelVersion::getDeleted, 0)
        );

        if (versionCount >= maxVersionsPerModel) {
            throw new BusinessException(
                    String.format("模型版本数量超过限制: 最多 %d 个，当前 %d 个",
                            maxVersionsPerModel, versionCount)
            );
        }

        LambdaQueryWrapper<ModelVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ModelVersion::getModelId, dto.getModelId())
                .eq(ModelVersion::getVersion, dto.getVersion())
                .eq(ModelVersion::getDeleted, 0);
        if (versionMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("版本号已存在");
        }

        ModelVersion version = new ModelVersion();
        version.setVersionId(IdGenerator.generateVersionId());
        version.setModelId(dto.getModelId());
        version.setVersion(dto.getVersion());
        version.setStage(CommonConstants.STAGE_DEVELOPMENT);
        version.setDescription(dto.getDescription());
        version.setArtifactPath(dto.getArtifactPath());
        version.setMetrics(dto.getMetrics());
        version.setParameters(dto.getParameters());
        version.setDataset(dto.getDataset());
        version.setCommitHash(dto.getCommitHash());
        version.setCreatedBy(dto.getCreatedBy());

        versionMapper.insert(version);
        log.info("模型版本创建成功: versionId={}, modelId={}, version={}",
                version.getVersionId(), dto.getModelId(), dto.getVersion());
        return version;
    }

    @Cacheable(value = "modelVersion", key = "#versionId", unless = "#result == null")
    public ModelVersion getById(String versionId) {
        ModelVersion version = versionMapper.selectById(versionId);
        if (version == null) {
            throw new BusinessException(404, "模型版本不存在");
        }
        return version;
    }

    public List<ModelVersion> listByModelId(String modelId) {
        return versionMapper.selectByModelId(modelId);
    }

    public PageResult<ModelVersion> listByModelIdPaged(String modelId, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<ModelVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ModelVersion::getModelId, modelId)
                .eq(ModelVersion::getDeleted, 0)
                .orderByDesc(ModelVersion::getCreatedAt);

        IPage<ModelVersion> page = versionMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page);
    }

    @Cacheable(value = "modelVersionLatest", key = "#modelId + '_' + #stage", unless = "#result == null")
    public ModelVersion getLatestByStage(String modelId, String stage) {
        return versionMapper.selectLatestByStage(modelId, stage);
    }

    @CacheEvict(value = {"modelVersion", "modelVersionLatest"}, allEntries = true)
    @Transactional(rollbackFor = Exception.class)
    public ModelVersion transitionStage(StageTransitionDTO dto) {
        ModelVersion version = getById(dto.getVersionId());
        String fromStage = version.getStage();

        validateStageTransition(fromStage, dto.getToStage());

        version.setStage(dto.getToStage());
        version.setUpdatedAt(LocalDateTime.now());
        versionMapper.updateById(version);

        StageTransitionLog logEntry = new StageTransitionLog();
        logEntry.setLogId(IdGenerator.generateId("log"));
        logEntry.setVersionId(dto.getVersionId());
        logEntry.setFromStage(fromStage);
        logEntry.setToStage(dto.getToStage());
        logEntry.setReason(dto.getReason());
        logEntry.setCreatedBy(dto.getOperatedBy());
        transitionLogMapper.insert(logEntry);

        log.info("模型版本阶段流转: versionId={}, {} -> {}", dto.getVersionId(), fromStage, dto.getToStage());
        return version;
    }

    private void validateStageTransition(String from, String to) {
        List<String> validStages = List.of(
                CommonConstants.STAGE_DEVELOPMENT,
                CommonConstants.STAGE_STAGING,
                CommonConstants.STAGE_PRODUCTION,
                CommonConstants.STAGE_ARCHIVED
        );

        if (!validStages.contains(to)) {
            throw new BusinessException("无效的目标阶段: " + to);
        }

        if (CommonConstants.STAGE_ARCHIVED.equals(from)) {
            throw new BusinessException("已归档的版本无法变更阶段");
        }
    }

    public List<StageTransitionLog> getTransitionLogs(String versionId) {
        return transitionLogMapper.selectByVersionId(versionId);
    }

    public PageResult<StageTransitionLog> getTransitionLogsPaged(String versionId, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<StageTransitionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StageTransitionLog::getVersionId, versionId)
                .orderByDesc(StageTransitionLog::getCreatedAt);

        IPage<StageTransitionLog> page = transitionLogMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page);
    }

    @CacheEvict(value = {"modelVersion", "modelVersionLatest"}, key = "#versionId")
    @Transactional(rollbackFor = Exception.class)
    public void deleteVersion(String versionId) {
        ModelVersion version = getById(versionId);
        version.setDeleted(1);
        version.setUpdatedAt(LocalDateTime.now());
        versionMapper.updateById(version);
        log.info("模型版本已删除: versionId={}", versionId);
    }

    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void cleanupOldTransitionLogs() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(transitionLogRetentionDays);
        LambdaQueryWrapper<StageTransitionLog> wrapper = new LambdaQueryWrapper<>();
        wrapper.lt(StageTransitionLog::getCreatedAt, cutoffTime);

        long deletedCount = transitionLogMapper.delete(wrapper);
        log.info("清理旧的阶段流转日志: 删除 {} 条记录（保留 {} 天内）", deletedCount, transitionLogRetentionDays);
    }
}
