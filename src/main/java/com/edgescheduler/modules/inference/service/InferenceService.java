package com.edgescheduler.modules.inference.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edgescheduler.common.exception.BusinessException;
import com.edgescheduler.common.exception.ValidationException;
import com.edgescheduler.common.util.IdGenerator;
import com.edgescheduler.modules.inference.domain.AiModel;
import com.edgescheduler.modules.inference.domain.InferenceTask;
import com.edgescheduler.modules.inference.domain.ModelVersionRelease;
import com.edgescheduler.modules.inference.mapper.AiModelMapper;
import com.edgescheduler.modules.inference.mapper.InferenceTaskMapper;
import com.edgescheduler.modules.inference.mapper.ModelVersionReleaseMapper;
import com.alibaba.fastjson2.JSON;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class InferenceService {

    private final AiModelMapper aiModelMapper;
    private final InferenceTaskMapper inferenceTaskMapper;
    private final ModelVersionReleaseMapper releaseMapper;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    private final PriorityBlockingQueue<InferenceTask> taskQueue = new PriorityBlockingQueue<>(
            1000, Comparator.comparingInt(InferenceTask::getPriority).reversed());

    private final Map<String, Boolean> runningTasks = new ConcurrentHashMap<>();
    private final Map<String, String> modelDefaultVersions = new ConcurrentHashMap<>();
    private final AtomicBoolean releaseInProgress = new AtomicBoolean(false);

    private static final int MAX_CONCURRENT_TASKS = 10;
    private static final Pattern VERSION_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");
    private static final int MAX_VERSION_HISTORY = 50;
    private static final int MAX_MODEL_NAME_LENGTH = 128;
    private static final int MAX_MODEL_VERSION_LENGTH = 32;
    private static final int MAX_MODEL_TYPE_LENGTH = 64;
    private static final int MAX_MODEL_PATH_LENGTH = 512;
    private static final int MAX_DESCRIPTION_LENGTH = 1024;
    private static final int MAX_CHANGELOG_LENGTH = 4096;
    private static final int MAX_DEVICE_ID_LENGTH = 128;
    private static final int MAX_RELEASE_NOTES_LENGTH = 2048;
    private static final int MAX_DEPRECATE_REASON_LENGTH = 1024;
    private static final int MAX_INPUT_DATA_SIZE = 10 * 1024 * 1024;
    private static final int MIN_PRIORITY = 1;
    private static final int MAX_PRIORITY = 10;
    private static final int MAX_VERSION_COMPONENTS = 3;
    private static final int MAX_VERSION_COMPONENT_VALUE = 999;

    private void validateString(String value, String fieldName, int maxLength, boolean allowEmpty) {
        if (value == null) {
            throw new ValidationException(fieldName + " cannot be null");
        }
        if (!allowEmpty && value.trim().isEmpty()) {
            throw new ValidationException(fieldName + " cannot be empty");
        }
        if (value.length() > maxLength) {
            throw new ValidationException(fieldName + " exceeds maximum length of " + maxLength);
        }
    }

    private void validateModelCommon(AiModel model) {
        validateString(model.getModelName(), "Model name", MAX_MODEL_NAME_LENGTH, false);
        validateString(model.getModelVersion(), "Model version", MAX_MODEL_VERSION_LENGTH, false);

        if (model.getModelType() != null && !model.getModelType().trim().isEmpty()) {
            validateString(model.getModelType(), "Model type", MAX_MODEL_TYPE_LENGTH, true);
        }
        if (model.getModelPath() != null && !model.getModelPath().trim().isEmpty()) {
            validateString(model.getModelPath(), "Model path", MAX_MODEL_PATH_LENGTH, true);
        }
        if (model.getVersionDescription() != null && !model.getVersionDescription().trim().isEmpty()) {
            validateString(model.getVersionDescription(), "Version description", MAX_DESCRIPTION_LENGTH, true);
        }
        if (model.getChangeLog() != null && !model.getChangeLog().trim().isEmpty()) {
            validateString(model.getChangeLog(), "Change log", MAX_CHANGELOG_LENGTH, true);
        }
        if (model.getDeprecatedReason() != null && !model.getDeprecatedReason().trim().isEmpty()) {
            validateString(model.getDeprecatedReason(), "Deprecate reason", MAX_DEPRECATE_REASON_LENGTH, true);
        }
    }

    private void validateVersionFormat(String version) {
        if (!isValidVersion(version)) {
            throw new ValidationException("版本号格式不正确，应为x.y.z格式，每个组件不超过" + MAX_VERSION_COMPONENT_VALUE);
        }
    }

    private void validateDeviceId(String deviceId) {
        if (deviceId != null && !deviceId.trim().isEmpty()) {
            validateString(deviceId, "Device ID", MAX_DEVICE_ID_LENGTH, true);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<AiModel> registerModel(AiModel model) {
        try {
            validateModelCommon(model);
            validateVersionFormat(model.getModelVersion());
        } catch (ValidationException e) {
            return Mono.error(e);
        }

        AiModel existing = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getModelName, model.getModelName())
                        .eq(AiModel::getModelVersion, model.getModelVersion()));

        if (existing != null) {
            return Mono.error(new BusinessException("该版本的模型已存在"));
        }

        String modelId = model.getModelId();
        if (modelId == null || modelId.isEmpty()) {
            modelId = IdGenerator.generateModelId();
        }

        model.setModelId(modelId);
        model.setDeployStatus("IDLE");
        model.setDeployedDevices(0);
        model.setVersionStatus("DRAFT");
        model.setIsDefaultVersion(false);
        model.setDeprecated(false);

        long count = aiModelMapper.selectCount(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getModelName, model.getModelName()));
        if (count == 0) {
            model.setIsDefaultVersion(true);
            modelDefaultVersions.put(model.getModelName(), model.getModelVersion());
        }

        aiModelMapper.insert(model);

        if (model.getIsDefaultVersion()) {
            updateDefaultVersion(model.getModelName(), model.getModelVersion());
        }

        updateMetrics("model_registered", model);
        return Mono.just(model);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<AiModel> createNewVersion(String parentModelId, AiModel newVersion) {
        try {
            if (parentModelId == null || parentModelId.trim().isEmpty()) {
                return Mono.error(new ValidationException("父模型ID不能为空"));
            }
            validateModelCommon(newVersion);
            validateVersionFormat(newVersion.getModelVersion());
        } catch (ValidationException e) {
            return Mono.error(e);
        }

        AiModel parentModel = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>().eq(AiModel::getModelId, parentModelId));

        if (parentModel == null) {
            return Mono.error(new BusinessException("父模型不存在"));
        }

        AiModel existingVersion = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getModelName, parentModel.getModelName())
                        .eq(AiModel::getModelVersion, newVersion.getModelVersion()));

        if (existingVersion != null) {
            return Mono.error(new BusinessException("该版本号已存在"));
        }

        if (compareVersions(newVersion.getModelVersion(), parentModel.getModelVersion()) <= 0) {
            return Mono.error(new BusinessException("新版本号必须大于父版本号"));
        }

        AiModel newModel = new AiModel();
        newModel.setModelId(IdGenerator.generateModelId());
        newModel.setModelName(parentModel.getModelName());
        newModel.setModelVersion(newVersion.getModelVersion());
        newModel.setModelType(parentModel.getModelType());
        newModel.setModelPath(newVersion.getModelPath() != null ? newVersion.getModelPath() : parentModel.getModelPath());
        newModel.setModelSize(newVersion.getModelSize() != null ? newVersion.getModelSize() : parentModel.getModelSize());
        newModel.setModelConfig(newVersion.getModelConfig() != null ? newVersion.getModelConfig() : parentModel.getModelConfig());
        newModel.setDeployStatus("IDLE");
        newModel.setDeployedDevices(0);
        newModel.setParentModelId(parentModelId);
        newModel.setVersionStatus("DRAFT");
        newModel.setVersionDescription(newVersion.getVersionDescription());
        newModel.setChangeLog(newVersion.getChangeLog());
        newModel.setCompatibilityCheck(newVersion.getCompatibilityCheck());
        newModel.setTrainedAt(newVersion.getTrainedAt());
        newModel.setTrainingDataset(newVersion.getTrainingDataset());
        newModel.setAccuracyMetrics(newVersion.getAccuracyMetrics());
        newModel.setIsDefaultVersion(false);
        newModel.setDeprecated(false);

        aiModelMapper.insert(newModel);

        updateMetrics("model_version_created", newModel);
        return Mono.just(newModel);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<AiModel> setDefaultVersion(String modelName, String version) {
        AiModel model = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getModelName, modelName)
                        .eq(AiModel::getModelVersion, version));

        if (model == null) {
            return Mono.error(new BusinessException("模型版本不存在"));
        }

        if (model.getDeprecated()) {
            return Mono.error(new BusinessException("已废弃的版本不能设为默认版本"));
        }

        aiModelMapper.selectList(
                        new LambdaQueryWrapper<AiModel>()
                                .eq(AiModel::getModelName, modelName))
                .forEach(m -> {
                    m.setIsDefaultVersion(m.getModelVersion().equals(version));
                    aiModelMapper.updateById(m);
                });

        modelDefaultVersions.put(modelName, version);

        updateMetrics("model_default_version_set", model);
        return Mono.just(model);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<AiModel> deployModel(String modelId, String deviceId) {
        try {
            if (modelId == null || modelId.trim().isEmpty()) {
                return Mono.error(new ValidationException("模型ID不能为空"));
            }
            validateDeviceId(deviceId);
        } catch (ValidationException e) {
            return Mono.error(e);
        }

        AiModel model = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>().eq(AiModel::getModelId, modelId));
        if (model == null) {
            return Mono.error(new BusinessException("模型不存在"));
        }

        if (model.getDeprecated()) {
            return Mono.error(new BusinessException("模型版本已废弃，无法部署"));
        }

        if (!"RELEASED".equals(model.getVersionStatus()) && !"DRAFT".equals(model.getVersionStatus())) {
            return Mono.error(new BusinessException("模型版本尚未发布，无法部署"));
        }

        model.setDeployStatus("DEPLOYED");
        model.setDeployedDevices(model.getDeployedDevices() + 1);
        model.setDeployedAt(LocalDateTime.now());
        aiModelMapper.updateById(model);

        redisTemplate.opsForSet().add("model:deployed:" + modelId, deviceId).subscribe();

        updateMetrics("model_deployed", model);
        return Mono.just(model);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<AiModel> releaseModelVersion(String modelId, String releaseType,
                                              String releaseNotes, List<String> grayscaleDevices) {
        try {
            if (modelId == null || modelId.trim().isEmpty()) {
                return Mono.error(new ValidationException("模型ID不能为空"));
            }
            if (releaseType != null && !releaseType.trim().isEmpty()) {
                validateString(releaseType, "Release type", 32, true);
            }
            if (releaseNotes != null && !releaseNotes.trim().isEmpty()) {
                validateString(releaseNotes, "Release notes", MAX_RELEASE_NOTES_LENGTH, true);
            }
            if (grayscaleDevices != null && !grayscaleDevices.isEmpty()) {
                for (String device : grayscaleDevices) {
                    validateDeviceId(device);
                }
            }
        } catch (ValidationException e) {
            return Mono.error(e);
        }

        AiModel model = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>().eq(AiModel::getModelId, modelId));

        if (model == null) {
            return Mono.error(new BusinessException("模型不存在"));
        }

        if (!"DRAFT".equals(model.getVersionStatus())) {
            return Mono.error(new BusinessException("只有草稿状态的版本才能发布"));
        }

        if (releaseInProgress.get()) {
            return Mono.error(new BusinessException("有发布任务正在进行中，请稍后再试"));
        }

        releaseInProgress.set(true);
        try {
            ModelVersionRelease release = new ModelVersionRelease();
            release.setReleaseId(IdGenerator.generateId("rel"));
            release.setModelId(modelId);
            release.setModelVersion(model.getModelVersion());
            release.setReleaseType(releaseType != null ? releaseType : "MINOR");
            release.setReleaseStatus("IN_PROGRESS");
            release.setReleaseNotes(releaseNotes);
            release.setGrayscalePercentage(grayscaleDevices != null && !grayscaleDevices.isEmpty() ? 100 : 100);
            release.setGrayscaleDevices(grayscaleDevices);
            release.setSuccessCount(0);
            release.setFailureCount(0);
            release.setRollbackCount(0);
            release.setScheduledAt(LocalDateTime.now());
            release.setStartedAt(LocalDateTime.now());

            releaseMapper.insert(release);

            model.setVersionStatus("RELEASED");
            aiModelMapper.updateById(model);

            release.setReleaseStatus("COMPLETED");
            release.setCompletedAt(LocalDateTime.now());
            releaseMapper.updateById(release);

            updateMetrics("model_version_released", model);

            return Mono.just(model);
        } finally {
            releaseInProgress.set(false);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<AiModel> rollbackModelVersion(String modelId) {
        try {
            if (modelId == null || modelId.trim().isEmpty()) {
                return Mono.error(new ValidationException("模型ID不能为空"));
            }
        } catch (ValidationException e) {
            return Mono.error(e);
        }

        AiModel currentModel = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>().eq(AiModel::getModelId, modelId));

        if (currentModel == null) {
            return Mono.error(new BusinessException("模型不存在"));
        }

        if (currentModel.getParentModelId() == null) {
            return Mono.error(new BusinessException("没有父版本可以回滚"));
        }

        AiModel parentModel = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>().eq(AiModel::getModelId, currentModel.getParentModelId()));

        if (parentModel == null) {
            return Mono.error(new BusinessException("父模型不存在"));
        }

        currentModel.setVersionStatus("ROLLED_BACK");
        currentModel.setDeprecated(true);
        currentModel.setDeprecatedAt(LocalDateTime.now());
        currentModel.setDeprecatedReason("用户主动回滚");
        aiModelMapper.updateById(currentModel);

        if (currentModel.getIsDefaultVersion()) {
            parentModel.setIsDefaultVersion(true);
            aiModelMapper.updateById(parentModel);
            modelDefaultVersions.put(parentModel.getModelName(), parentModel.getModelVersion());
        }

        ModelVersionRelease release = new ModelVersionRelease();
        release.setReleaseId(IdGenerator.generateId("rel"));
        release.setModelId(modelId);
        release.setModelVersion(currentModel.getModelVersion());
        release.setReleaseType("ROLLBACK");
        release.setReleaseStatus("COMPLETED");
        release.setReleaseNotes("回滚到版本: " + parentModel.getModelVersion());
        release.setRollbackCount(1);
        release.setStartedAt(LocalDateTime.now());
        release.setCompletedAt(LocalDateTime.now());
        releaseMapper.insert(release);

        updateMetrics("model_version_rolled_back", currentModel);

        return Mono.just(parentModel);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<AiModel> deprecateModelVersion(String modelId, String reason) {
        try {
            if (modelId == null || modelId.trim().isEmpty()) {
                return Mono.error(new ValidationException("模型ID不能为空"));
            }
            if (reason != null && !reason.trim().isEmpty()) {
                validateString(reason, "Deprecate reason", MAX_DEPRECATE_REASON_LENGTH, true);
            }
        } catch (ValidationException e) {
            return Mono.error(e);
        }

        AiModel model = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>().eq(AiModel::getModelId, modelId));

        if (model == null) {
            return Mono.error(new BusinessException("模型不存在"));
        }

        if (model.getIsDefaultVersion()) {
            return Mono.error(new BusinessException("默认版本不能废弃，请先设置其他版本为默认版本"));
        }

        model.setDeprecated(true);
        model.setDeprecatedAt(LocalDateTime.now());
        model.setDeprecatedReason(reason);
        aiModelMapper.updateById(model);

        updateMetrics("model_version_deprecated", model);
        return Mono.just(model);
    }

    public Flux<AiModel> getModelVersionHistory(String modelName) {
        List<AiModel> models = aiModelMapper.selectList(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getModelName, modelName)
                        .orderByDesc(AiModel::getCreatedAt)
                        .last("LIMIT " + MAX_VERSION_HISTORY));
        return Flux.fromIterable(models);
    }

    public Mono<Map<String, Object>> getModelVersionTree(String modelName) {
        List<AiModel> allVersions = aiModelMapper.selectList(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getModelName, modelName)
                        .orderByAsc(AiModel::getCreatedAt));

        Map<String, Object> result = new HashMap<>();
        result.put("modelName", modelName);

        List<Map<String, Object>> versionTree = new ArrayList<>();
        Map<String, AiModel> versionMap = new HashMap<>();

        for (AiModel model : allVersions) {
            versionMap.put(model.getModelId(), model);

            Map<String, Object> node = new HashMap<>();
            node.put("modelId", model.getModelId());
            node.put("version", model.getModelVersion());
            node.put("status", model.getVersionStatus());
            node.put("isDefault", model.getIsDefaultVersion());
            node.put("deprecated", model.getDeprecated());
            node.put("description", model.getVersionDescription());
            node.put("createdAt", model.getCreatedAt());
            node.put("parentModelId", model.getParentModelId());
            versionTree.add(node);
        }

        result.put("versions", versionTree);
        result.put("totalVersions", allVersions.size());
        result.put("defaultVersion", modelDefaultVersions.get(modelName));

        return Mono.just(result);
    }

    public Mono<AiModel> getDefaultVersion(String modelName) {
        String defaultVersion = modelDefaultVersions.get(modelName);
        if (defaultVersion == null) {
            AiModel firstModel = aiModelMapper.selectOne(
                    new LambdaQueryWrapper<AiModel>()
                            .eq(AiModel::getModelName, modelName)
                            .eq(AiModel::getIsDefaultVersion, true)
                            .last("LIMIT 1"));
            if (firstModel == null) {
                return Mono.error(new BusinessException("模型不存在默认版本"));
            }
            modelDefaultVersions.put(modelName, firstModel.getModelVersion());
            return Mono.just(firstModel);
        }

        return getModelVersion(modelName, defaultVersion);
    }

    public Mono<AiModel> getModelVersion(String modelName, String version) {
        AiModel model = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getModelName, modelName)
                        .eq(AiModel::getModelVersion, version));
        if (model == null) {
            return Mono.error(new BusinessException("模型版本不存在"));
        }
        return Mono.just(model);
    }

    public Mono<Boolean> checkCompatibility(String modelId, String deviceId, Map<String, Object> runtimeEnv) {
        AiModel model = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>().eq(AiModel::getModelId, modelId));

        if (model == null) {
            return Mono.error(new BusinessException("模型不存在"));
        }

        if (model.getCompatibilityCheck() == null || model.getCompatibilityCheck().isEmpty()) {
            return Mono.just(true);
        }

        Map<String, Object> requirements = model.getCompatibilityCheck();
        for (Map.Entry<String, Object> req : requirements.entrySet()) {
            Object envValue = runtimeEnv.get(req.getKey());
            if (envValue == null) {
                return Mono.just(false);
            }
            if (!envValue.equals(req.getValue())) {
                return Mono.just(false);
            }
        }

        return Mono.just(true);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<InferenceTask> submitTask(String modelId, String deviceId,
                                            Map<String, Object> inputData, Integer priority) {
        try {
            if (modelId == null || modelId.trim().isEmpty()) {
                return Mono.error(new ValidationException("模型ID不能为空"));
            }
            validateDeviceId(deviceId);

            if (inputData == null || inputData.isEmpty()) {
                return Mono.error(new ValidationException("输入数据不能为空"));
            }

            String inputJson = JSON.toJSONString(inputData);
            if (inputJson.length() > MAX_INPUT_DATA_SIZE) {
                return Mono.error(new ValidationException("输入数据过大，最大支持 " + MAX_INPUT_DATA_SIZE + " 字节"));
            }

            if (priority != null) {
                if (priority < MIN_PRIORITY || priority > MAX_PRIORITY) {
                    return Mono.error(new ValidationException("优先级必须在 " + MIN_PRIORITY + " 到 " + MAX_PRIORITY + " 之间"));
                }
            }
        } catch (ValidationException e) {
            return Mono.error(e);
        }

        AiModel model = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>().eq(AiModel::getModelId, modelId));
        if (model == null) {
            return Mono.error(new BusinessException("模型不存在"));
        }

        if (model.getDeprecated()) {
            return Mono.error(new BusinessException("模型版本已废弃"));
        }

        InferenceTask task = new InferenceTask();
        task.setTaskId(IdGenerator.generateTaskId());
        task.setModelId(modelId);
        task.setModelName(model.getModelName());
        task.setModelVersion(model.getModelVersion());
        task.setDeviceId(deviceId);
        task.setTaskStatus("PENDING");
        task.setPriority(priority != null ? priority : 5);
        task.setInputData(inputData);
        task.setScheduledTime(LocalDateTime.now());
        task.setInputDataChecksum(calculateInputChecksum(inputData));
        task.setRollbackAvailable(true);

        Map<String, Object> versionSnapshot = new HashMap<>();
        versionSnapshot.put("modelId", model.getModelId());
        versionSnapshot.put("modelName", model.getModelName());
        versionSnapshot.put("modelVersion", model.getModelVersion());
        versionSnapshot.put("modelType", model.getModelType());
        versionSnapshot.put("modelConfig", model.getModelConfig());
        versionSnapshot.put("versionStatus", model.getVersionStatus());
        versionSnapshot.put("snapshotTime", LocalDateTime.now().toString());
        task.setModelVersionSnapshot(versionSnapshot);

        inferenceTaskMapper.insert(task);
        taskQueue.offer(task);

        updateMetrics("task_submitted", model);
        scheduleTaskExecution();

        return Mono.just(task);
    }

    @Scheduled(fixedDelay = 1000)
    public void scheduleTaskExecution() {
        int runningCount = (int) runningTasks.values().stream().filter(Boolean::booleanValue).count();
        int availableSlots = MAX_CONCURRENT_TASKS - runningCount;

        for (int i = 0; i < availableSlots && !taskQueue.isEmpty(); i++) {
            InferenceTask task = taskQueue.poll();
            if (task != null && !runningTasks.containsKey(task.getTaskId())) {
                runningTasks.put(task.getTaskId(), true);
                executeTask(task)
                        .subscribeOn(Schedulers.boundedElastic())
                        .subscribe();
            }
        }
    }

    private Mono<InferenceTask> executeTask(InferenceTask task) {
        return Mono.fromCallable(() -> {
            Timer.Sample sample = Timer.start(meterRegistry);
            try {
                task.setTaskStatus("RUNNING");
                task.setStartTime(LocalDateTime.now());
                inferenceTaskMapper.updateById(task);

                Map<String, Object> result = performInference(task);

                task.setTaskStatus("COMPLETED");
                task.setInferenceResult(result);
                task.setCompletedTime(LocalDateTime.now());
                task.setInferenceDuration(java.time.Duration.between(
                        task.getStartTime(), task.getCompletedTime()).toMillis());

                inferenceTaskMapper.updateById(task);
                updateMetrics("task_completed", task);

                return task;
            } catch (Exception e) {
                log.error("Inference task failed: {}", task.getTaskId(), e);
                task.setTaskStatus("FAILED");
                task.setErrorMessage(e.getMessage());
                task.setCompletedTime(LocalDateTime.now());
                inferenceTaskMapper.updateById(task);
                updateMetrics("task_failed", task);
                return task;
            } finally {
                runningTasks.remove(task.getTaskId());
                sample.stop(Timer.builder("edge_scheduler_inference_task_duration")
                        .description("Duration of inference task execution")
                        .tag("modelId", task.getModelId())
                        .tag("modelVersion", task.getModelVersion())
                        .register(meterRegistry));
            }
        });
    }

    private Map<String, Object> performInference(InferenceTask task) {
        Map<String, Object> result = new HashMap<>();
        result.put("taskId", task.getTaskId());
        result.put("modelId", task.getModelId());
        result.put("modelVersion", task.getModelVersion());
        result.put("inferenceTime", System.currentTimeMillis());

        Map<String, Object> inputData = task.getInputData();
        if (inputData != null && !inputData.isEmpty()) {
            Random random = new Random();
            result.put("confidence", 0.75 + random.nextDouble() * 0.25);
            result.put("prediction", "class_" + random.nextInt(10));
            result.put("processing_time_ms", 50 + random.nextInt(200));

            List<Map<String, Object>> detections = new ArrayList<>();
            for (int i = 0; i < random.nextInt(5) + 1; i++) {
                Map<String, Object> detection = new HashMap<>();
                detection.put("label", "object_" + i);
                detection.put("bbox", Arrays.asList(
                        random.nextDouble() * 100,
                        random.nextDouble() * 100,
                        random.nextDouble() * 100,
                        random.nextDouble() * 100
                ));
                detection.put("score", 0.5 + random.nextDouble() * 0.5);
                detections.add(detection);
            }
            result.put("detections", detections);
        }

        result.put("versionSnapshot", task.getModelVersionSnapshot());
        return result;
    }

    public Mono<InferenceTask> getTaskResult(String taskId) {
        InferenceTask task = inferenceTaskMapper.selectOne(
                new LambdaQueryWrapper<InferenceTask>().eq(InferenceTask::getTaskId, taskId));
        if (task == null) {
            return Mono.error(new BusinessException("任务不存在"));
        }
        return Mono.just(task);
    }

    public Flux<InferenceTask> getTasksByStatus(String status) {
        List<InferenceTask> tasks = inferenceTaskMapper.selectList(
                new LambdaQueryWrapper<InferenceTask>()
                        .eq(status != null, InferenceTask::getTaskStatus, status)
                        .orderByDesc(InferenceTask::getPriority)
                        .orderByDesc(InferenceTask::getCreatedAt));
        return Flux.fromIterable(tasks);
    }

    public Flux<AiModel> getModels(String modelType) {
        List<AiModel> models = aiModelMapper.selectList(
                new LambdaQueryWrapper<AiModel>()
                        .eq(modelType != null, AiModel::getModelType, modelType)
                        .eq(AiModel::getDeprecated, false)
                        .orderByDesc(AiModel::getCreatedAt));
        return Flux.fromIterable(models);
    }

    public Flux<AiModel> getAllModelVersions(String modelName) {
        List<AiModel> models = aiModelMapper.selectList(
                new LambdaQueryWrapper<AiModel>()
                        .eq(AiModel::getModelName, modelName)
                        .orderByDesc(AiModel::getCreatedAt));
        return Flux.fromIterable(models);
    }

    public Mono<AiModel> getModel(String modelId) {
        AiModel model = aiModelMapper.selectOne(
                new LambdaQueryWrapper<AiModel>().eq(AiModel::getModelId, modelId));
        if (model == null) {
            return Mono.error(new BusinessException("模型不存在"));
        }
        return Mono.just(model);
    }

    public Flux<ModelVersionRelease> getReleaseHistory(String modelId) {
        List<ModelVersionRelease> releases = releaseMapper.selectList(
                new LambdaQueryWrapper<ModelVersionRelease>()
                        .eq(ModelVersionRelease::getModelId, modelId)
                        .orderByDesc(ModelVersionRelease::getCreatedAt));
        return Flux.fromIterable(releases);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<Void> cancelTask(String taskId) {
        InferenceTask task = inferenceTaskMapper.selectOne(
                new LambdaQueryWrapper<InferenceTask>().eq(InferenceTask::getTaskId, taskId));
        if (task == null) {
            return Mono.error(new BusinessException("任务不存在"));
        }

        if ("PENDING".equals(task.getTaskStatus())) {
            taskQueue.removeIf(t -> t.getTaskId().equals(taskId));
            task.setTaskStatus("CANCELLED");
            inferenceTaskMapper.updateById(task);
        }

        return Mono.empty();
    }

    public Mono<Map<String, Object>> getQueueStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("pendingTasks", taskQueue.size());
        status.put("runningTasks", runningTasks.size());
        status.put("maxConcurrentTasks", MAX_CONCURRENT_TASKS);
        status.put("releaseInProgress", releaseInProgress.get());
        return Mono.just(status);
    }

    public Mono<Map<String, Object>> getVersionStats(String modelName) {
        Map<String, Object> stats = new HashMap<>();

        List<AiModel> allVersions = aiModelMapper.selectList(
                new LambdaQueryWrapper<AiModel>().eq(AiModel::getModelName, modelName));

        stats.put("totalVersions", allVersions.size());
        stats.put("defaultVersion", modelDefaultVersions.get(modelName));

        long releasedCount = allVersions.stream()
                .filter(m -> "RELEASED".equals(m.getVersionStatus())).count();
        stats.put("releasedVersions", releasedCount);

        long deprecatedCount = allVersions.stream()
                .filter(m -> Boolean.TRUE.equals(m.getDeprecated())).count();
        stats.put("deprecatedVersions", deprecatedCount);

        long deployedCount = allVersions.stream()
                .filter(m -> "DEPLOYED".equals(m.getDeployStatus())).count();
        stats.put("deployedVersions", deployedCount);

        int totalDeployedDevices = allVersions.stream()
                .mapToInt(m -> m.getDeployedDevices() != null ? m.getDeployedDevices() : 0).sum();
        stats.put("totalDeployedDevices", totalDeployedDevices);

        return Mono.just(stats);
    }

    private void updateDefaultVersion(String modelName, String version) {
        modelDefaultVersions.put(modelName, version);
    }

    private boolean isValidVersion(String version) {
        if (version == null) return false;
        Matcher matcher = VERSION_PATTERN.matcher(version);
        if (!matcher.matches()) return false;
        for (int i = 1; i <= MAX_VERSION_COMPONENTS; i++) {
            int component = Integer.parseInt(matcher.group(i));
            if (component < 0 || component > MAX_VERSION_COMPONENT_VALUE) {
                return false;
            }
        }
        return true;
    }

    private int compareVersions(String v1, String v2) {
        Matcher m1 = VERSION_PATTERN.matcher(v1);
        Matcher m2 = VERSION_PATTERN.matcher(v2);
        if (!m1.matches() || !m2.matches()) {
            return v1.compareTo(v2);
        }

        int major1 = Integer.parseInt(m1.group(1));
        int minor1 = Integer.parseInt(m1.group(2));
        int patch1 = Integer.parseInt(m1.group(3));

        int major2 = Integer.parseInt(m2.group(1));
        int minor2 = Integer.parseInt(m2.group(2));
        int patch2 = Integer.parseInt(m2.group(3));

        if (major1 != major2) return Integer.compare(major1, major2);
        if (minor1 != minor2) return Integer.compare(minor1, minor2);
        return Integer.compare(patch1, patch2);
    }

    private String calculateInputChecksum(Map<String, Object> inputData) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String dataStr = JSON.toJSONString(inputData);
            byte[] hash = digest.digest(dataStr.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString().substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            return String.valueOf(inputData.hashCode());
        }
    }

    private void updateMetrics(String action, AiModel model) {
        Counter.builder("edge_scheduler_inference_operations_total")
                .description("Total inference operations")
                .tag("action", action)
                .tag("modelId", model.getModelId())
                .tag("modelName", model.getModelName())
                .tag("modelVersion", model.getModelVersion())
                .register(meterRegistry)
                .increment();
    }

    private void updateMetrics(String action, InferenceTask task) {
        Counter.builder("edge_scheduler_inference_operations_total")
                .description("Total inference operations")
                .tag("action", action)
                .tag("modelId", task.getModelId())
                .tag("modelVersion", task.getModelVersion())
                .register(meterRegistry)
                .increment();
    }
}
