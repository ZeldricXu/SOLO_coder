package com.edgescheduler.inference.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edgescheduler.common.exception.BusinessException;
import com.edgescheduler.inference.dto.AiModelDTO;
import com.edgescheduler.inference.dto.InferenceTaskDTO;
import com.edgescheduler.inference.entity.AiModel;
import com.edgescheduler.inference.entity.InferenceTask;
import com.edgescheduler.inference.mapper.AiModelMapper;
import com.edgescheduler.inference.mapper.InferenceTaskMapper;
import com.edgescheduler.inference.service.InferenceService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class InferenceServiceImpl implements InferenceService {

    private final AiModelMapper modelMapper;
    private final InferenceTaskMapper taskMapper;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional
    public AiModelDTO registerModel(AiModelDTO modelDTO) {
        AiModel model = new AiModel();
        BeanUtils.copyProperties(modelDTO, model);
        model.setModelId("model_" + IdUtil.getSnowflakeNextIdStr());
        model.setStatus(AiModel.Status.UPLOADED);
        modelMapper.insert(model);
        meterRegistry.counter("inference.model.register.total").increment();
        log.info("AI Model registered: {}", model.getModelId());
        return convertModelToDTO(model);
    }

    @Override
    public AiModelDTO getModel(String modelId) {
        AiModel model = getModelEntity(modelId);
        return convertModelToDTO(model);
    }

    @Override
    public List<AiModel> listModels(String status, String modelType) {
        LambdaQueryWrapper<AiModel> wrapper = new LambdaQueryWrapper<>();
        if (status != null) wrapper.eq(AiModel::getStatus, status);
        if (modelType != null) wrapper.eq(AiModel::getModelType, modelType);
        wrapper.orderByDesc(AiModel::getCreatedAt);
        return modelMapper.selectList(wrapper);
    }

    @Override
    @Transactional
    public AiModelDTO updateModelStatus(String modelId, String status) {
        AiModel model = getModelEntity(modelId);
        model.setStatus(status);
        modelMapper.updateById(model);
        log.info("AI Model status updated: {} -> {}", modelId, status);
        return convertModelToDTO(model);
    }

    @Override
    @Transactional
    public void deleteModel(String modelId) {
        AiModel model = getModelEntity(modelId);
        modelMapper.deleteById(model.getId());
        log.info("AI Model deleted: {}", modelId);
    }

    @Override
    @Transactional
    public InferenceTaskDTO createTask(InferenceTaskDTO taskDTO) {
        AiModel model = modelMapper.selectByModelId(taskDTO.getModelId());
        if (model == null) {
            throw BusinessException.notFound("Model not found: " + taskDTO.getModelId());
        }

        InferenceTask task = new InferenceTask();
        BeanUtils.copyProperties(taskDTO, task);
        task.setTaskId("task_" + IdUtil.getSnowflakeNextIdStr());
        task.setStatus(InferenceTask.Status.PENDING);
        task.setProgress(0.0);
        task.setPriority(taskDTO.getPriority() != null ? taskDTO.getPriority() : InferenceTask.Priority.NORMAL);
        task.setTaskType(taskDTO.getTaskType() != null ? taskDTO.getTaskType() : InferenceTask.TaskType.BATCH);
        taskMapper.insert(task);

        meterRegistry.counter("inference.task.create.total").increment();
        log.info("Inference task created: {}", task.getTaskId());

        return convertTaskToDTO(task);
    }

    @Override
    public InferenceTaskDTO getTask(String taskId) {
        InferenceTask task = getTaskEntity(taskId);
        return convertTaskToDTO(task);
    }

    @Override
    public List<InferenceTask> listTasks(String deviceKey, String status, int limit) {
        LambdaQueryWrapper<InferenceTask> wrapper = new LambdaQueryWrapper<>();
        if (deviceKey != null) wrapper.eq(InferenceTask::getDeviceKey, deviceKey);
        if (status != null) wrapper.eq(InferenceTask::getStatus, status);
        wrapper.orderByDesc(InferenceTask::getCreatedAt);
        wrapper.last("LIMIT " + limit);
        return taskMapper.selectList(wrapper);
    }

    @Override
    @Transactional
    public InferenceTaskDTO updateTaskStatus(String taskId, String status, Map<String, Object> result, Long inferenceTime) {
        InferenceTask task = getTaskEntity(taskId);
        task.setStatus(status);

        if (result != null) {
            task.setInferenceResult(result);
        }
        if (inferenceTime != null) {
            task.setInferenceTimeMs(inferenceTime);
        }
        if (InferenceTask.Status.RUNNING.equals(status) && task.getStartedAt() == null) {
            task.setStartedAt(LocalDateTime.now());
        }
        if (InferenceTask.Status.COMPLETED.equals(status) || InferenceTask.Status.FAILED.equals(status)) {
            task.setCompletedAt(LocalDateTime.now());
            task.setProgress(InferenceTask.Status.COMPLETED.equals(status) ? 1.0 : 0.0);
        }

        taskMapper.updateById(task);
        log.info("Inference task status updated: {} -> {}", taskId, status);

        return convertTaskToDTO(task);
    }

    @Override
    @Transactional
    public InferenceTaskDTO scheduleTask(String taskId) {
        InferenceTask task = getTaskEntity(taskId);
        if (!InferenceTask.Status.PENDING.equals(task.getStatus())) {
            throw new BusinessException("Task is not in pending status: " + task.getStatus());
        }

        task.setStatus(InferenceTask.Status.SCHEDULED);
        task.setScheduledAt(LocalDateTime.now());
        taskMapper.updateById(task);

        log.info("Inference task scheduled: {}", taskId);
        return convertTaskToDTO(task);
    }

    @Override
    @Transactional
    public InferenceTaskDTO executeTask(String taskId) {
        InferenceTask task = getTaskEntity(taskId);
        if (!InferenceTask.Status.SCHEDULED.equals(task.getStatus())) {
            throw new BusinessException("Task is not in scheduled status: " + task.getStatus());
        }

        task.setStatus(InferenceTask.Status.RUNNING);
        task.setStartedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        log.info("Inference task executing: {}", taskId);
        return convertTaskToDTO(task);
    }

    @Override
    @Transactional
    public void cancelTask(String taskId) {
        InferenceTask task = getTaskEntity(taskId);
        if (InferenceTask.Status.RUNNING.equals(task.getStatus())) {
            throw new BusinessException("Cannot cancel running task");
        }

        task.setStatus(InferenceTask.Status.CANCELLED);
        task.setCompletedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        log.info("Inference task cancelled: {}", taskId);
    }

    @Override
    @Transactional
    @Scheduled(fixedDelayString = "${edge.scheduler.inference.schedule-interval-ms:10000}")
    public List<InferenceTask> schedulePendingTasks(int batchSize) {
        List<InferenceTask> pendingTasks = taskMapper.selectPendingTasks(
                InferenceTask.Status.PENDING, batchSize);

        for (InferenceTask task : pendingTasks) {
            task.setStatus(InferenceTask.Status.SCHEDULED);
            task.setScheduledAt(LocalDateTime.now());
            taskMapper.updateById(task);
        }

        if (!pendingTasks.isEmpty()) {
            log.info("Scheduled {} pending inference tasks", pendingTasks.size());
            meterRegistry.counter("inference.task.schedule.total").increment(pendingTasks.size());
        }

        return pendingTasks;
    }

    @Override
    public List<InferenceTask> getDeviceTasks(String deviceKey, int limit) {
        return taskMapper.selectByDeviceKey(deviceKey, limit);
    }

    @Override
    public Map<String, Object> getTaskStatus(String taskId) {
        InferenceTask task = getTaskEntity(taskId);
        Map<String, Object> status = new HashMap<>();
        status.put("taskId", task.getTaskId());
        status.put("status", task.getStatus());
        status.put("progress", task.getProgress());
        status.put("scheduledAt", task.getScheduledAt());
        status.put("startedAt", task.getStartedAt());
        status.put("completedAt", task.getCompletedAt());
        status.put("inferenceTimeMs", task.getInferenceTimeMs());
        return status;
    }

    private AiModel getModelEntity(String modelId) {
        AiModel model = modelMapper.selectByModelId(modelId);
        if (model == null) {
            throw BusinessException.notFound("Model not found: " + modelId);
        }
        return model;
    }

    private InferenceTask getTaskEntity(String taskId) {
        InferenceTask task = taskMapper.selectByTaskId(taskId);
        if (task == null) {
            throw BusinessException.notFound("Task not found: " + taskId);
        }
        return task;
    }

    private AiModelDTO convertModelToDTO(AiModel model) {
        AiModelDTO dto = new AiModelDTO();
        BeanUtils.copyProperties(model, dto);
        return dto;
    }

    private InferenceTaskDTO convertTaskToDTO(InferenceTask task) {
        InferenceTaskDTO dto = new InferenceTaskDTO();
        BeanUtils.copyProperties(task, dto);
        return dto;
    }
}
