package com.apishield.fl.service.impl;

import com.apishield.common.exception.BusinessException;
import com.apishield.common.util.IdGenerator;
import com.apishield.fl.domain.FlClientUpdate;
import com.apishield.fl.domain.FlTrainingTask;
import com.apishield.fl.dto.FlClientUpdateRequest;
import com.apishield.fl.dto.FlTaskRequest;
import com.apishield.fl.service.FederatedLearningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class FederatedLearningServiceImpl implements FederatedLearningService {

    private final Map<String, FlTrainingTask> taskStore = new ConcurrentHashMap<>();
    private final Map<String, List<FlClientUpdate>> updateStore = new ConcurrentHashMap<>();

    @Override
    public FlTrainingTask createTask(FlTaskRequest request) {
        FlTrainingTask task = new FlTrainingTask();
        task.setId(IdGenerator.generateId("fl"));
        task.setTaskId(task.getId());
        task.setModelName(request.getModelName());
        task.setModelVersion(request.getModelVersion());
        task.setStatus(FlTrainingTask.TaskStatus.CREATED);
        task.setParticipantIds(request.getParticipantIds());
        task.setCurrentRound(0);
        task.setTotalRounds(request.getTotalRounds());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        if (request.getHyperparameters() != null) {
            task.getHyperparameters().putAll(request.getHyperparameters());
        }
        if (request.getInitialModel() != null) {
            task.getGlobalModel().putAll(request.getInitialModel());
        }

        taskStore.put(task.getTaskId(), task);
        updateStore.put(task.getTaskId(), new ArrayList<>());

        log.info("Created FL task: {}, model: {}, participants: {}", 
                task.getTaskId(), request.getModelName(), request.getParticipantIds().size());
        return task;
    }

    @Override
    public FlTrainingTask getTask(String taskId) {
        FlTrainingTask task = taskStore.get(taskId);
        if (task == null) {
            throw new BusinessException("NOT_FOUND", "FL任务不存在: " + taskId);
        }
        return task;
    }

    @Override
    public FlTrainingTask startTask(String taskId) {
        FlTrainingTask task = getTask(taskId);
        if (task.getStatus() != FlTrainingTask.TaskStatus.CREATED) {
            throw new BusinessException("FL_001", "任务状态不允许启动: " + task.getStatus());
        }

        task.setStatus(FlTrainingTask.TaskStatus.TRAINING);
        task.setCurrentRound(1);
        task.setStartTime(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        log.info("Started FL task: {}, round 1 of {}", taskId, task.getTotalRounds());
        return task;
    }

    @Override
    public FlTrainingTask cancelTask(String taskId) {
        FlTrainingTask task = getTask(taskId);
        task.setStatus(FlTrainingTask.TaskStatus.CANCELLED);
        task.setEndTime(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        log.info("Cancelled FL task: {}", taskId);
        return task;
    }

    @Override
    public void submitClientUpdate(FlClientUpdateRequest request) {
        FlTrainingTask task = getTask(request.getTaskId());
        if (task.getStatus() != FlTrainingTask.TaskStatus.TRAINING) {
            throw new BusinessException("FL_001", "任务未在训练中，无法提交更新");
        }
        if (request.getRoundNumber() != task.getCurrentRound()) {
            throw new BusinessException("FL_001", 
                String.format("轮次不匹配，当前%d轮，提交的是%d轮", 
                    task.getCurrentRound(), request.getRoundNumber()));
        }

        FlClientUpdate update = new FlClientUpdate();
        update.setId(IdGenerator.generateId("flu"));
        update.setUpdateId(update.getId());
        update.setTaskId(request.getTaskId());
        update.setClientId(request.getClientId());
        update.setRoundNumber(request.getRoundNumber());
        update.setEncryptedGradients(request.getEncryptedGradients());
        update.setEncryptedWeights(request.getEncryptedWeights());
        update.setSampleCount(request.getSampleCount());
        update.setLocalLoss(request.getLocalLoss());
        update.setSubmittedAt(LocalDateTime.now());
        update.setStatus("RECEIVED");
        update.setCreatedAt(LocalDateTime.now());
        update.setUpdatedAt(LocalDateTime.now());

        updateStore.get(request.getTaskId()).add(update);
        log.info("Received client update from {} for task {}, round {}", 
                request.getClientId(), request.getTaskId(), request.getRoundNumber());
    }

    @Override
    public List<FlClientUpdate> getClientUpdates(String taskId, int roundNumber) {
        return updateStore.getOrDefault(taskId, Collections.emptyList()).stream()
                .filter(u -> u.getRoundNumber() == roundNumber)
                .collect(Collectors.toList());
    }

    @Override
    public FlTrainingTask aggregateGradients(String taskId, int roundNumber) {
        FlTrainingTask task = getTask(taskId);
        List<FlClientUpdate> updates = getClientUpdates(taskId, roundNumber);

        if (updates.isEmpty()) {
            throw new BusinessException("FL_002", "没有客户端更新可聚合");
        }

        task.setStatus(FlTrainingTask.TaskStatus.AGGREGATING);
        task.setUpdatedAt(LocalDateTime.now());

        int totalSamples = updates.stream().mapToInt(FlClientUpdate::getSampleCount).sum();
        Map<String, Object> aggregated = new HashMap<>();

        for (FlClientUpdate update : updates) {
            double weight = (double) update.getSampleCount() / totalSamples;
            Map<String, Object> gradients = update.getEncryptedGradients();
            if (gradients != null) {
                for (Map.Entry<String, Object> entry : gradients.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    if (value instanceof double[]) {
                        double[] existing = (double[]) aggregated.getOrDefault(key, new double[((double[]) value).length]);
                        double[] current = (double[]) value;
                        for (int i = 0; i < current.length; i++) {
                            existing[i] += current[i] * weight;
                        }
                        aggregated.put(key, existing);
                    } else if (value instanceof Double) {
                        double existing = (double) aggregated.getOrDefault(key, 0.0);
                        aggregated.put(key, existing + (Double) value * weight);
                    }
                }
            }
        }

        double avgLoss = updates.stream().mapToDouble(FlClientUpdate::getLocalLoss).average().orElse(0.0);
        task.setLoss(avgLoss);
        task.getAggregatedGradients().clear();
        task.getAggregatedGradients().putAll(aggregated);

        log.info("Aggregated gradients for task {}, round {}, updates: {}, avg loss: {}", 
                taskId, roundNumber, updates.size(), avgLoss);
        return task;
    }

    @Override
    public FlTrainingTask updateGlobalModel(String taskId) {
        FlTrainingTask task = getTask(taskId);
        Map<String, Object> gradients = task.getAggregatedGradients();
        Map<String, Object> model = task.getGlobalModel();
        double learningRate = (double) task.getHyperparameters().getOrDefault("learningRate", 0.01);

        for (Map.Entry<String, Object> entry : gradients.entrySet()) {
            String key = entry.getKey();
            Object gradValue = entry.getValue();
            Object modelValue = model.get(key);

            if (gradValue instanceof double[] && modelValue instanceof double[]) {
                double[] g = (double[]) gradValue;
                double[] m = (double[]) modelValue;
                for (int i = 0; i < m.length; i++) {
                    m[i] -= learningRate * g[i];
                }
            } else if (gradValue instanceof Double && modelValue instanceof Double) {
                model.put(key, (Double) modelValue - learningRate * (Double) gradValue);
            }
        }

        if (task.getCurrentRound() >= task.getTotalRounds()) {
            task.setStatus(FlTrainingTask.TaskStatus.COMPLETED);
            task.setEndTime(LocalDateTime.now());
            task.setAccuracy(0.85);
        } else {
            task.setCurrentRound(task.getCurrentRound() + 1);
            task.setStatus(FlTrainingTask.TaskStatus.TRAINING);
        }
        task.setUpdatedAt(LocalDateTime.now());

        log.info("Updated global model for task {}, new round: {}", taskId, task.getCurrentRound());
        return task;
    }

    @Override
    public Map<String, Object> getGlobalModel(String taskId) {
        FlTrainingTask task = getTask(taskId);
        return task.getGlobalModel();
    }

    @Override
    public List<FlTrainingTask> getTasksByStatus(FlTrainingTask.TaskStatus status) {
        return taskStore.values().stream()
                .filter(t -> t.getStatus() == status)
                .collect(Collectors.toList());
    }
}
