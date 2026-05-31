package com.datamasker.application.coordinator;

import com.datamasker.application.service.FederationService;
import com.datamasker.domain.federation.model.FederationTask;
import com.datamasker.domain.federation.model.GlobalModelUpdate;
import com.datamasker.infrastructure.config.FederationConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class FederationCoordinator {

    private final FederationService federationService;
    private final FederationConfig federationConfig;

    public GlobalModelUpdate coordinateRound(String taskId) {
        FederationTask task = federationService.getTaskInfo(taskId);

        if (task.getParticipantCount() < federationConfig.getMinParticipants()) {
            throw new IllegalStateException("Not enough participants for round: need "
                    + federationConfig.getMinParticipants() + ", got " + task.getParticipantCount());
        }

        if ("COMPLETED".equals(task.getStatus()) || "FAILED".equals(task.getStatus())) {
            throw new IllegalStateException("Task is already " + task.getStatus());
        }

        return federationService.aggregateAndUpdate(taskId);
    }

    public GlobalModelUpdate runTraining(String taskId, int maxRounds) {
        GlobalModelUpdate lastUpdate = null;

        for (int round = 0; round < maxRounds; round++) {
            try {
                FederationTask task = federationService.getTaskInfo(taskId);

                if ("COMPLETED".equals(task.getStatus())) {
                    break;
                }

                if ("FAILED".equals(task.getStatus())) {
                    break;
                }

                lastUpdate = coordinateRound(taskId);

                if (lastUpdate.getConvergenceMetric() >= federationConfig.getConvergenceThreshold()) {
                    break;
                }
            } catch (Exception e) {
                break;
            }
        }

        return lastUpdate;
    }

    public boolean isConverged(String taskId) {
        double convergence = federationService.checkConvergence(taskId);
        return convergence >= federationConfig.getConvergenceThreshold();
    }
}
