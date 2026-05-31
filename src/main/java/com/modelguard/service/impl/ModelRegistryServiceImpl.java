package com.modelguard.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelguard.dto.ModelRegistrationDTO;
import com.modelguard.dto.ModelVersionCreateDTO;
import com.modelguard.dto.StageTransitionDTO;
import com.modelguard.entity.ModelRegistration;
import com.modelguard.entity.ModelVersion;
import com.modelguard.entity.StageTransition;
import com.modelguard.exception.BusinessException;
import com.modelguard.exception.ResourceNotFoundException;
import com.modelguard.mapper.ModelRegistrationMapper;
import com.modelguard.mapper.ModelVersionMapper;
import com.modelguard.mapper.StageTransitionMapper;
import com.modelguard.service.ModelRegistryService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRegistryServiceImpl implements ModelRegistryService {

    private final ModelRegistrationMapper modelRegistrationMapper;
    private final ModelVersionMapper modelVersionMapper;
    private final StageTransitionMapper stageTransitionMapper;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    private static final String MODEL_CACHE_PREFIX = "model:";
    private static final String VERSION_CACHE_PREFIX = "model:version:";
    private static final String STAGE_NONE = "NONE";
    private static final String STAGE_STAGING = "STAGING";
    private static final String STAGE_PRODUCTION = "PRODUCTION";
    private static final String STAGE_ARCHIVED = "ARCHIVED";

    private static final List<String> STAGE_ORDER = Arrays.asList(
            STAGE_NONE, STAGE_STAGING, STAGE_PRODUCTION, STAGE_ARCHIVED
    );

    private final Counter modelRegisteredCounter;
    private final Counter versionCreatedCounter;
    private final Counter stageTransitionCounter;

    {
        modelRegisteredCounter = Counter.builder("model.registry.registered")
                .description("Models registered")
                .register(io.micrometer.core.instrument.Metrics.globalRegistry);
        versionCreatedCounter = Counter.builder("model.registry.versions.created")
                .description("Model versions created")
                .register(io.micrometer.core.instrument.Metrics.globalRegistry);
        stageTransitionCounter = Counter.builder("model.registry.stage.transitions")
                .description("Stage transitions")
                .register(io.micrometer.core.instrument.Metrics.globalRegistry);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<ModelRegistration> registerModel(ModelRegistrationDTO dto) {
        return Mono.fromCallable(() -> {
            String modelId = dto.getModelId() != null ? dto.getModelId() : "model_" + IdUtil.simpleUUID();

            LambdaQueryWrapper<ModelRegistration> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ModelRegistration::getModelId, modelId);
            if (modelRegistrationMapper.selectCount(wrapper) > 0) {
                throw new BusinessException("Model ID already exists: " + modelId);
            }

            ModelRegistration model = new ModelRegistration();
            model.setModelId(modelId);
            model.setModelName(dto.getModelName());
            model.setModelType(dto.getModelType());
            model.setDescription(dto.getDescription());
            model.setOwner(dto.getOwner());
            model.setDepartment(dto.getDepartment());
            model.setMetadata(dto.getMetadata());
            model.setTags(dto.getTags());
            model.setCurrentStage(STAGE_NONE);
            model.setStatus("active");
            model.setRegisteredAt(LocalDateTime.now());
            model.setLastModifiedAt(LocalDateTime.now());
            model.setLicense(dto.getLicense());
            model.setRepository(dto.getRepository());
            model.setDocumentationUrl(dto.getDocumentationUrl());

            modelRegistrationMapper.insert(model);
            modelRegisteredCounter.increment();

            String cacheKey = MODEL_CACHE_PREFIX + modelId;
            redisTemplate.opsForValue().set(cacheKey, toJson(model), Duration.ofMinutes(10)).subscribe();

            log.info("Model registered: {} ({})", model.getModelName(), modelId);
            return model;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<ModelRegistration> updateModel(String modelId, ModelRegistrationDTO dto) {
        return getModel(modelId)
                .flatMap(model -> Mono.fromCallable(() -> {
                    if (dto.getModelName() != null) model.setModelName(dto.getModelName());
                    if (dto.getModelType() != null) model.setModelType(dto.getModelType());
                    if (dto.getDescription() != null) model.setDescription(dto.getDescription());
                    if (dto.getOwner() != null) model.setOwner(dto.getOwner());
                    if (dto.getDepartment() != null) model.setDepartment(dto.getDepartment());
                    if (dto.getMetadata() != null) model.setMetadata(dto.getMetadata());
                    if (dto.getTags() != null) model.setTags(dto.getTags());
                    if (dto.getLicense() != null) model.setLicense(dto.getLicense());
                    if (dto.getRepository() != null) model.setRepository(dto.getRepository());
                    if (dto.getDocumentationUrl() != null) model.setDocumentationUrl(dto.getDocumentationUrl());
                    model.setLastModifiedAt(LocalDateTime.now());

                    modelRegistrationMapper.updateById(model);

                    String cacheKey = MODEL_CACHE_PREFIX + modelId;
                    redisTemplate.opsForValue().set(cacheKey, toJson(model), Duration.ofMinutes(10)).subscribe();

                    return model;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Void> deleteModel(String modelId) {
        return getModel(modelId)
                .flatMap(model -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<ModelVersion> versionWrapper = new LambdaQueryWrapper<>();
                    versionWrapper.eq(ModelVersion::getModelId, modelId);
                    List<ModelVersion> versions = modelVersionMapper.selectList(versionWrapper);
                    if (!versions.isEmpty()) {
                        throw new BusinessException("Cannot delete model with existing versions. Please delete versions first.");
                    }

                    modelRegistrationMapper.deleteById(model.getId());
                    String cacheKey = MODEL_CACHE_PREFIX + modelId;
                    redisTemplate.delete(cacheKey).subscribe();

                    log.info("Model deleted: {}", modelId);
                    return null;
                }).subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    @Override
    public Mono<ModelRegistration> getModel(String modelId) {
        String cacheKey = MODEL_CACHE_PREFIX + modelId;
        return redisTemplate.opsForValue().get(cacheKey)
                .flatMap(json -> Mono.justOrEmpty(fromJson(json, ModelRegistration.class)))
                .switchIfEmpty(Mono.fromCallable(() -> {
                    LambdaQueryWrapper<ModelRegistration> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(ModelRegistration::getModelId, modelId);
                    ModelRegistration model = modelRegistrationMapper.selectOne(wrapper);
                    if (model == null) {
                        throw new ResourceNotFoundException("Model not found: " + modelId);
                    }
                    redisTemplate.opsForValue().set(cacheKey, toJson(model), Duration.ofMinutes(10)).subscribe();
                    return model;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    public Mono<Page<ModelRegistration>> listModels(int page, int size, String modelType, String owner, String stage, String status) {
        return Mono.fromCallable(() -> {
            Page<ModelRegistration> pageParam = new Page<>(page, size);
            LambdaQueryWrapper<ModelRegistration> wrapper = new LambdaQueryWrapper<>();
            if (modelType != null) wrapper.eq(ModelRegistration::getModelType, modelType);
            if (owner != null) wrapper.eq(ModelRegistration::getOwner, owner);
            if (stage != null) wrapper.eq(ModelRegistration::getCurrentStage, stage);
            if (status != null) wrapper.eq(ModelRegistration::getStatus, status);
            wrapper.orderByDesc(ModelRegistration::getRegisteredAt);
            return modelRegistrationMapper.selectPage(pageParam, wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<ModelVersion> createVersion(ModelVersionCreateDTO dto) {
        return getModel(dto.getModelId())
                .flatMap(model -> Mono.fromCallable(() -> {
                    String version = dto.getVersion() != null ? dto.getVersion() : generateVersion(dto.getModelId());

                    LambdaQueryWrapper<ModelVersion> versionWrapper = new LambdaQueryWrapper<>();
                    versionWrapper.eq(ModelVersion::getModelId, dto.getModelId())
                            .eq(ModelVersion::getVersion, version);
                    if (modelVersionMapper.selectCount(versionWrapper) > 0) {
                        throw new BusinessException("Version already exists: " + version + " for model " + dto.getModelId());
                    }

                    LambdaQueryWrapper<ModelVersion> countWrapper = new LambdaQueryWrapper<>();
                    countWrapper.eq(ModelVersion::getModelId, dto.getModelId());
                    Integer versionNumber = Math.toIntExact(modelVersionMapper.selectCount(countWrapper) + 1);

                    ModelVersion modelVersion = new ModelVersion();
                    modelVersion.setModelId(dto.getModelId());
                    modelVersion.setVersion(version);
                    modelVersion.setVersionNumber(versionNumber);
                    modelVersion.setStage(STAGE_NONE);
                    modelVersion.setParentVersion(dto.getParentVersion());
                    modelVersion.setDescription(dto.getDescription());
                    modelVersion.setMetrics(dto.getMetrics());
                    modelVersion.setArtifacts(dto.getArtifacts());
                    modelVersion.setTrainingData(dto.getTrainingData());
                    modelVersion.setHyperparameters(dto.getHyperparameters());
                    modelVersion.setAlgorithm(dto.getAlgorithm());
                    modelVersion.setFramework(dto.getFramework());
                    modelVersion.setFrameworkVersion(dto.getFrameworkVersion());
                    modelVersion.setStatus("active");
                    modelVersion.setCreatedBy(dto.getCreatedBy());
                    modelVersion.setCreatedTime(LocalDateTime.now());
                    modelVersion.setChecksum(dto.getChecksum());
                    modelVersion.setModelSizeBytes(dto.getModelSizeBytes());
                    modelVersion.setEnvironment(dto.getEnvironment());
                    modelVersion.setDependencies(dto.getDependencies());
                    modelVersion.setNotes(dto.getNotes());
                    modelVersion.setApprovalStatus("pending");

                    modelVersionMapper.insert(modelVersion);
                    versionCreatedCounter.increment();

                    model.setLatestVersion(version);
                    model.setLastModifiedAt(LocalDateTime.now());
                    modelRegistrationMapper.updateById(model);

                    String cacheKey = VERSION_CACHE_PREFIX + dto.getModelId() + ":" + version;
                    redisTemplate.opsForValue().set(cacheKey, toJson(modelVersion), Duration.ofMinutes(10)).subscribe();

                    String modelCacheKey = MODEL_CACHE_PREFIX + dto.getModelId();
                    redisTemplate.opsForValue().set(modelCacheKey, toJson(model), Duration.ofMinutes(10)).subscribe();

                    log.info("Version created: {}@{}", dto.getModelId(), version);
                    return modelVersion;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private String generateVersion(String modelId) {
        LambdaQueryWrapper<ModelVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ModelVersion::getModelId, modelId)
                .orderByDesc(ModelVersion::getVersionNumber)
                .last("LIMIT 1");
        ModelVersion latest = modelVersionMapper.selectOne(wrapper);
        if (latest == null) {
            return "1.0.0";
        }
        String[] parts = latest.getVersion().split("\\.");
        int patch = Integer.parseInt(parts[2]) + 1;
        return parts[0] + "." + parts[1] + "." + patch;
    }

    @Override
    public Mono<ModelVersion> getVersion(String modelId, String version) {
        String cacheKey = VERSION_CACHE_PREFIX + modelId + ":" + version;
        return redisTemplate.opsForValue().get(cacheKey)
                .flatMap(json -> Mono.justOrEmpty(fromJson(json, ModelVersion.class)))
                .switchIfEmpty(Mono.fromCallable(() -> {
                    LambdaQueryWrapper<ModelVersion> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(ModelVersion::getModelId, modelId)
                            .eq(ModelVersion::getVersion, version);
                    ModelVersion modelVersion = modelVersionMapper.selectOne(wrapper);
                    if (modelVersion == null) {
                        throw new ResourceNotFoundException("Version not found: " + version + " for model " + modelId);
                    }
                    redisTemplate.opsForValue().set(cacheKey, toJson(modelVersion), Duration.ofMinutes(10)).subscribe();
                    return modelVersion;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    public Mono<List<ModelVersion>> listVersions(String modelId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ModelVersion> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ModelVersion::getModelId, modelId)
                    .orderByDesc(ModelVersion::getVersionNumber);
            return modelVersionMapper.selectList(wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Page<ModelVersion>> listVersionsPaged(String modelId, int page, int size, String stage) {
        return Mono.fromCallable(() -> {
            Page<ModelVersion> pageParam = new Page<>(page, size);
            LambdaQueryWrapper<ModelVersion> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ModelVersion::getModelId, modelId);
            if (stage != null) wrapper.eq(ModelVersion::getStage, stage);
            wrapper.orderByDesc(ModelVersion::getVersionNumber);
            return modelVersionMapper.selectPage(pageParam, wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<ModelVersion> getLatestVersion(String modelId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ModelVersion> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ModelVersion::getModelId, modelId)
                    .orderByDesc(ModelVersion::getVersionNumber)
                    .last("LIMIT 1");
            ModelVersion version = modelVersionMapper.selectOne(wrapper);
            if (version == null) {
                throw new ResourceNotFoundException("No versions found for model: " + modelId);
            }
            return version;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<ModelVersion> getVersionByStage(String modelId, String stage) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ModelVersion> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ModelVersion::getModelId, modelId)
                    .eq(ModelVersion::getStage, stage)
                    .orderByDesc(ModelVersion::getVersionNumber)
                    .last("LIMIT 1");
            ModelVersion version = modelVersionMapper.selectOne(wrapper);
            if (version == null) {
                throw new ResourceNotFoundException("No version in stage " + stage + " for model: " + modelId);
            }
            return version;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<StageTransition> transitionStage(StageTransitionDTO dto) {
        return getVersion(dto.getModelId(), dto.getVersion())
                .zipWith(getModel(dto.getModelId()))
                .flatMap(tuple -> {
                    ModelVersion version = tuple.getT1();
                    ModelRegistration model = tuple.getT2();

                    String fromStage = dto.getFromStage() != null ? dto.getFromStage() : version.getStage();
                    String toStage = dto.getToStage();

                    return validateStageTransition(fromStage, toStage, dto.getModelId(), dto.getVersion())
                            .filter(Boolean::booleanValue)
                            .switchIfEmpty(Mono.error(new BusinessException(
                                    String.format("Invalid stage transition: %s -> %s", fromStage, toStage))))
                            .flatMap(valid -> {
                                if (!"approved".equals(version.getApprovalStatus()) && STAGE_PRODUCTION.equals(toStage)) {
                                    return Mono.error(new BusinessException("Version must be approved before promoting to production"));
                                }

                                return Mono.fromCallable(() -> {
                                    String transitionId = "trans_" + IdUtil.simpleUUID();

                                    StageTransition transition = new StageTransition();
                                    transition.setTransitionId(transitionId);
                                    transition.setModelId(dto.getModelId());
                                    transition.setVersion(dto.getVersion());
                                    transition.setFromStage(fromStage);
                                    transition.setToStage(toStage);
                                    transition.setReason(dto.getReason());
                                    transition.setApprovalChecklist(dto.getApprovalChecklist());
                                    transition.setTransitionedBy(dto.getTransitionedBy());
                                    transition.setTransitionedAt(LocalDateTime.now());
                                    transition.setStatus("completed");

                                    stageTransitionMapper.insert(transition);
                                    stageTransitionCounter.increment();

                                    version.setStage(toStage);
                                    if (STAGE_PRODUCTION.equals(toStage)) {
                                        version.setDeployedAt(LocalDateTime.now());
                                    } else if (STAGE_ARCHIVED.equals(toStage)) {
                                        version.setArchivedAt(LocalDateTime.now());
                                        version.setStatus("archived");
                                    }
                                    modelVersionMapper.updateById(version);

                                    model.setCurrentStage(toStage);
                                    model.setLastModifiedAt(LocalDateTime.now());
                                    modelRegistrationMapper.updateById(model);

                                    demoteOtherVersions(dto.getModelId(), dto.getVersion(), toStage);

                                    evictVersionCaches(dto.getModelId(), dto.getVersion());
                                    evictModelCache(dto.getModelId());

                                    log.info("Stage transition completed: {}@{} {} -> {}",
                                            dto.getModelId(), dto.getVersion(), fromStage, toStage);
                                    return transition;
                                }).subscribeOn(Schedulers.boundedElastic());
                            });
                });
    }

    private void demoteOtherVersions(String modelId, String currentVersion, String targetStage) {
        if (!STAGE_PRODUCTION.equals(targetStage) && !STAGE_STAGING.equals(targetStage)) {
            return;
        }

        LambdaQueryWrapper<ModelVersion> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ModelVersion::getModelId, modelId)
                .eq(ModelVersion::getStage, targetStage)
                .ne(ModelVersion::getVersion, currentVersion);

        List<ModelVersion> versions = modelVersionMapper.selectList(wrapper);
        for (ModelVersion v : versions) {
            v.setStage(STAGE_NONE);
            modelVersionMapper.updateById(v);
            evictVersionCaches(modelId, v.getVersion());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<StageTransition> rollbackTransition(String transitionId, String reason, String rolledBackBy) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<StageTransition> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(StageTransition::getTransitionId, transitionId);
            StageTransition originalTransition = stageTransitionMapper.selectOne(wrapper);
            if (originalTransition == null) {
                throw new ResourceNotFoundException("Transition not found: " + transitionId);
            }
            if ("rolled_back".equals(originalTransition.getStatus())) {
                throw new BusinessException("Transition already rolled back");
            }

            return originalTransition;
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMap(originalTransition -> getVersion(originalTransition.getModelId(), originalTransition.getVersion())
                        .zipWith(getModel(originalTransition.getModelId()))
                        .flatMap(tuple -> {
                            ModelVersion version = tuple.getT1();
                            ModelRegistration model = tuple.getT2();

                            return Mono.fromCallable(() -> {
                                String rollbackTransitionId = "trans_" + IdUtil.simpleUUID();

                                StageTransition rollback = new StageTransition();
                                rollback.setTransitionId(rollbackTransitionId);
                                rollback.setModelId(originalTransition.getModelId());
                                rollback.setVersion(originalTransition.getVersion());
                                rollback.setFromStage(originalTransition.getToStage());
                                rollback.setToStage(originalTransition.getFromStage());
                                rollback.setReason("Rollback: " + reason);
                                rollback.setTransitionedBy(rolledBackBy);
                                rollback.setTransitionedAt(LocalDateTime.now());
                                rollback.setStatus("completed");
                                rollback.setRollbackFromTransitionId(transitionId);

                                Map<String, Object> rollbackInfo = new HashMap<>();
                                rollbackInfo.put("original_transition_id", transitionId);
                                rollbackInfo.put("original_reason", originalTransition.getReason());
                                rollback.setRollbackInfo(rollbackInfo);

                                stageTransitionMapper.insert(rollback);

                                originalTransition.setStatus("rolled_back");
                                stageTransitionMapper.updateById(originalTransition);

                                version.setStage(originalTransition.getFromStage());
                                modelVersionMapper.updateById(version);

                                if (STAGE_PRODUCTION.equals(originalTransition.getToStage()) ||
                                        STAGE_STAGING.equals(originalTransition.getToStage())) {
                                    model.setCurrentStage(originalTransition.getFromStage());
                                    model.setLastModifiedAt(LocalDateTime.now());
                                    modelRegistrationMapper.updateById(model);
                                    evictModelCache(originalTransition.getModelId());
                                }

                                evictVersionCaches(originalTransition.getModelId(), originalTransition.getVersion());

                                log.info("Stage rollback completed: {}@{} {} -> {}",
                                        originalTransition.getModelId(), originalTransition.getVersion(),
                                        originalTransition.getToStage(), originalTransition.getFromStage());
                                return rollback;
                            }).subscribeOn(Schedulers.boundedElastic());
                        }));
    }

    @Override
    public Mono<List<StageTransition>> getTransitionHistory(String modelId, String version) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<StageTransition> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(StageTransition::getModelId, modelId);
            if (version != null) wrapper.eq(StageTransition::getVersion, version);
            wrapper.orderByDesc(StageTransition::getTransitionedAt);
            return stageTransitionMapper.selectList(wrapper);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<ModelVersion> approveVersion(String modelId, String version, String approvedBy, String notes) {
        return getVersion(modelId, version)
                .flatMap(v -> Mono.fromCallable(() -> {
                    v.setApprovalStatus("approved");
                    v.setApprovedBy(approvedBy);
                    v.setApprovedAt(LocalDateTime.now());
                    if (notes != null) {
                        v.setNotes(v.getNotes() != null ? v.getNotes() + "\n\nApproval notes: " + notes : "Approval notes: " + notes);
                    }
                    modelVersionMapper.updateById(v);
                    evictVersionCaches(modelId, version);
                    log.info("Version approved: {}@{} by {}", modelId, version, approvedBy);
                    return v;
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<ModelVersion> archiveVersion(String modelId, String version) {
        StageTransitionDTO dto = new StageTransitionDTO();
        dto.setModelId(modelId);
        dto.setVersion(version);
        dto.setToStage(STAGE_ARCHIVED);
        dto.setReason("Archived");
        return transitionStage(dto)
                .flatMap(t -> getVersion(modelId, version));
    }

    @Override
    public Mono<Map<String, Object>> getModelSummary(String modelId) {
        return getModel(modelId)
                .zipWith(listVersions(modelId))
                .map(tuple -> {
                    ModelRegistration model = tuple.getT1();
                    List<ModelVersion> versions = tuple.getT2();

                    Map<String, Object> summary = new HashMap<>();
                    summary.put("model_id", model.getModelId());
                    summary.put("model_name", model.getModelName());
                    summary.put("model_type", model.getModelType());
                    summary.put("current_stage", model.getCurrentStage());
                    summary.put("total_versions", versions.size());
                    summary.put("latest_version", model.getLatestVersion());
                    summary.put("owner", model.getOwner());
                    summary.put("department", model.getDepartment());
                    summary.put("registered_at", model.getRegisteredAt());
                    summary.put("status", model.getStatus());

                    Map<String, ModelVersion> stageVersions = new HashMap<>();
                    for (ModelVersion v : versions) {
                        if (v.getStage() != null && !STAGE_NONE.equals(v.getStage()) && !STAGE_ARCHIVED.equals(v.getStage())) {
                            stageVersions.put(v.getStage(), v);
                        }
                    }
                    summary.put("stage_versions", stageVersions);

                    Map<String, Long> stageCounts = versions.stream()
                            .collect(Collectors.groupingBy(
                                    v -> v.getStage() != null ? v.getStage() : STAGE_NONE,
                                    Collectors.counting()
                            ));
                    summary.put("version_stage_distribution", stageCounts);

                    return summary;
                });
    }

    @Override
    @Scheduled(cron = "0 0 2 * * ?")
    public Flux<ModelVersion> promoteVersionsScheduled() {
        log.info("Starting scheduled version promotion check");
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<ModelVersion> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(ModelVersion::getStage, STAGE_STAGING)
                    .eq(ModelVersion::getApprovalStatus, "approved")
                    .lt(ModelVersion::getCreatedTime, LocalDateTime.now().minusDays(7));
            return modelVersionMapper.selectList(wrapper);
        }).subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable)
                .flatMap(version -> {
                    log.info("Auto-promoting version {}@{} to production after 7 days in staging",
                            version.getModelId(), version.getVersion());
                    StageTransitionDTO dto = new StageTransitionDTO();
                    dto.setModelId(version.getModelId());
                    dto.setVersion(version.getVersion());
                    dto.setFromStage(STAGE_STAGING);
                    dto.setToStage(STAGE_PRODUCTION);
                    dto.setReason("Auto-promotion after 7 days in staging with approval");
                    dto.setTransitionedBy("system");
                    return transitionStage(dto).thenReturn(version);
                })
                .onErrorContinue((e, o) -> log.error("Auto-promotion failed for {}: {}", o, e.getMessage()));
    }

    @Override
    public Mono<Boolean> validateStageTransition(String fromStage, String toStage, String modelId, String version) {
        return Mono.fromCallable(() -> {
            if (!STAGE_ORDER.contains(fromStage) || !STAGE_ORDER.contains(toStage)) {
                return false;
            }

            int fromIndex = STAGE_ORDER.indexOf(fromStage);
            int toIndex = STAGE_ORDER.indexOf(toStage);

            if (toIndex < fromIndex && !STAGE_ARCHIVED.equals(toStage)) {
                return true;
            }

            if (STAGE_ARCHIVED.equals(fromStage)) {
                return false;
            }

            if (STAGE_PRODUCTION.equals(toStage)) {
                return true;
            }

            if (STAGE_STAGING.equals(toStage) && STAGE_NONE.equals(fromStage)) {
                return true;
            }

            return toIndex >= fromIndex;
        });
    }

    @Override
    public Mono<Map<String, Object>> compareVersions(String modelId, String version1, String version2) {
        return getVersion(modelId, version1)
                .zipWith(getVersion(modelId, version2))
                .map(tuple -> {
                    ModelVersion v1 = tuple.getT1();
                    ModelVersion v2 = tuple.getT2();

                    Map<String, Object> comparison = new HashMap<>();
                    comparison.put("model_id", modelId);
                    comparison.put("version_1", version1);
                    comparison.put("version_2", version2);

                    Map<String, Object> differences = new HashMap<>();
                    if (!Objects.equals(v1.getAlgorithm(), v2.getAlgorithm())) {
                        differences.put("algorithm", Map.of("v1", v1.getAlgorithm(), "v2", v2.getAlgorithm()));
                    }
                    if (!Objects.equals(v1.getFramework(), v2.getFramework())) {
                        differences.put("framework", Map.of("v1", v1.getFramework(), "v2", v2.getFramework()));
                    }
                    if (!Objects.equals(v1.getHyperparameters(), v2.getHyperparameters())) {
                        differences.put("hyperparameters", Map.of("v1", v1.getHyperparameters(), "v2", v2.getHyperparameters()));
                    }
                    if (!Objects.equals(v1.getMetrics(), v2.getMetrics())) {
                        differences.put("metrics", Map.of("v1", v1.getMetrics(), "v2", v2.getMetrics()));
                    }
                    if (!Objects.equals(v1.getModelSizeBytes(), v2.getModelSizeBytes())) {
                        differences.put("model_size_bytes", Map.of("v1", v1.getModelSizeBytes(), "v2", v2.getModelSizeBytes()));
                    }

                    comparison.put("differences", differences);
                    comparison.put("version_1_details", v1);
                    comparison.put("version_2_details", v2);

                    return comparison;
                });
    }

    private void evictVersionCaches(String modelId, String version) {
        String cacheKey = VERSION_CACHE_PREFIX + modelId + ":" + version;
        redisTemplate.delete(cacheKey).subscribe();
    }

    private void evictModelCache(String modelId) {
        String cacheKey = MODEL_CACHE_PREFIX + modelId;
        redisTemplate.delete(cacheKey).subscribe();
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BusinessException("Failed to serialize object", e);
        }
    }

    private <T> T fromJson(String json, Class<T> clazz) {
        try {
            return objectMapper.readValue(json, clazz);
        } catch (Exception e) {
            return null;
        }
    }
}
