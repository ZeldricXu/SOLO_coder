package com.apishield.fl.service;

import com.apishield.application.service.ApplicationService;
import com.apishield.fl.domain.FlClientUpdate;
import com.apishield.fl.domain.FlTrainingTask;
import com.apishield.fl.dto.FlClientUpdateRequest;
import com.apishield.fl.dto.FlTaskRequest;
import java.util.List;
import java.util.Map;

public interface FederatedLearningService extends ApplicationService {
    FlTrainingTask createTask(FlTaskRequest request);
    FlTrainingTask getTask(String taskId);
    FlTrainingTask startTask(String taskId);
    FlTrainingTask cancelTask(String taskId);
    void submitClientUpdate(FlClientUpdateRequest request);
    List<FlClientUpdate> getClientUpdates(String taskId, int roundNumber);
    FlTrainingTask aggregateGradients(String taskId, int roundNumber);
    FlTrainingTask updateGlobalModel(String taskId);
    Map<String, Object> getGlobalModel(String taskId);
    List<FlTrainingTask> getTasksByStatus(FlTrainingTask.TaskStatus status);
}
