package com.datamasker.application.service;

import com.datamasker.domain.federation.aggregator.GradientAggregator;
import com.datamasker.domain.federation.model.FederationParticipant;
import com.datamasker.domain.federation.model.FederationTask;
import com.datamasker.domain.federation.model.GlobalModelUpdate;
import com.datamasker.infrastructure.config.FederationConfig;
import com.datamasker.infrastructure.persistence.entity.FederationTaskEntity;
import com.datamasker.infrastructure.persistence.mapper.FederationTaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FederationService {

    private final GradientAggregator gradientAggregator;
    private final FederationConfig federationConfig;
    private final FederationTaskMapper federationTaskMapper;

    private final ConcurrentHashMap<String, FederationTask> taskCache = new ConcurrentHashMap<>();

    public FederationTask createTask(int minParticipants) {
        String taskId = UUID.randomUUID().toString().replace("-", "");

        FederationTask task = new FederationTask();
        task.setTaskId(taskId);
        task.setRoundNumber(0);
        task.setParticipantCount(0);
        task.setStatus("PENDING");
        task.setParticipants(new ArrayList<>());
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        FederationTaskEntity entity = new FederationTaskEntity();
        entity.setTaskId(taskId);
        entity.setRoundNumber(0);
        entity.setParticipantCount(0);
        entity.setStatus("PENDING");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        federationTaskMapper.insert(entity);

        taskCache.put(taskId, task);
        return task;
    }

    public void submitGradient(String taskId, String participantId, String encryptedGradient,
                               String localModelHash, int dataSampleCount) {
        FederationTask task = findTaskOrThrow(taskId);

        if (!"PENDING".equals(task.getStatus()) && !"RUNNING".equals(task.getStatus())) {
            throw new IllegalStateException("Task is not accepting gradients, current status: " + task.getStatus());
        }

        FederationParticipant participant = new FederationParticipant();
        participant.setParticipantId(participantId);
        participant.setTaskId(taskId);
        participant.setEncryptedGradient(encryptedGradient);
        participant.setLocalModelHash(localModelHash);
        participant.setDataSampleCount(dataSampleCount);
        participant.setSubmittedAt(LocalDateTime.now());

        task.getParticipants().add(participant);
        task.setParticipantCount(task.getParticipants().size());
        task.setStatus("RUNNING");
        task.setUpdatedAt(LocalDateTime.now());

        updateEntity(task);
    }

    public GlobalModelUpdate aggregateAndUpdate(String taskId) {
        FederationTask task = findTaskOrThrow(taskId);

        if (task.getParticipantCount() < federationConfig.getMinParticipants()) {
            throw new IllegalStateException("Not enough participants: need " + federationConfig.getMinParticipants()
                    + ", got " + task.getParticipantCount());
        }

        List<String> gradients = task.getParticipants().stream()
                .map(FederationParticipant::getEncryptedGradient)
                .collect(Collectors.toList());

        String aggregatedGradient = gradientAggregator.aggregateGradients(gradients, task.getParticipantCount());
        String modelHash = gradientAggregator.computeModelHash(aggregatedGradient);
        double convergence = gradientAggregator.computeConvergence(gradients);

        task.setRoundNumber(task.getRoundNumber() + 1);
        task.setGlobalModelHash(modelHash);
        task.setUpdatedAt(LocalDateTime.now());

        if (convergence >= federationConfig.getConvergenceThreshold()) {
            task.setStatus("COMPLETED");
        }

        updateEntity(task);

        GlobalModelUpdate update = new GlobalModelUpdate();
        update.setTaskId(taskId);
        update.setRoundNumber(task.getRoundNumber());
        update.setAggregatedGradient(aggregatedGradient);
        update.setGlobalModelHash(modelHash);
        update.setParticipantCount(task.getParticipantCount());
        update.setConvergenceMetric(convergence);
        update.setUpdatedAt(LocalDateTime.now());

        return update;
    }

    public FederationTask getTaskInfo(String taskId) {
        return findTaskOrThrow(taskId);
    }

    public double checkConvergence(String taskId) {
        FederationTask task = findTaskOrThrow(taskId);

        if (task.getParticipants() == null || task.getParticipants().isEmpty()) {
            return 0.0;
        }

        List<String> gradients = task.getParticipants().stream()
                .map(FederationParticipant::getEncryptedGradient)
                .collect(Collectors.toList());

        return gradientAggregator.computeConvergence(gradients);
    }

    private FederationTask findTaskOrThrow(String taskId) {
        FederationTask task = taskCache.get(taskId);
        if (task == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        return task;
    }

    private void updateEntity(FederationTask task) {
        FederationTaskEntity entity = new FederationTaskEntity();
        entity.setTaskId(task.getTaskId());
        entity.setRoundNumber(task.getRoundNumber());
        entity.setParticipantCount(task.getParticipantCount());
        entity.setStatus(task.getStatus());
        entity.setGlobalModelHash(task.getGlobalModelHash());
        entity.setUpdatedAt(task.getUpdatedAt());

        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FederationTaskEntity> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>();
        wrapper.eq(FederationTaskEntity::getTaskId, task.getTaskId());
        federationTaskMapper.update(entity, wrapper);
    }
}
