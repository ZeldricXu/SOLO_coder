package com.edgescheduler.inference.service;

import com.edgescheduler.inference.dto.AiModelDTO;
import com.edgescheduler.inference.dto.InferenceTaskDTO;
import com.edgescheduler.inference.entity.AiModel;
import com.edgescheduler.inference.entity.InferenceTask;

import java.util.List;
import java.util.Map;

public interface InferenceService {

    AiModelDTO registerModel(AiModelDTO modelDTO);

    AiModelDTO getModel(String modelId);

    List<AiModel> listModels(String status, String modelType);

    AiModelDTO updateModelStatus(String modelId, String status);

    void deleteModel(String modelId);

    InferenceTaskDTO createTask(InferenceTaskDTO taskDTO);

    InferenceTaskDTO getTask(String taskId);

    List<InferenceTask> listTasks(String deviceKey, String status, int limit);

    InferenceTaskDTO updateTaskStatus(String taskId, String status, Map<String, Object> result, Long inferenceTime);

    InferenceTaskDTO scheduleTask(String taskId);

    InferenceTaskDTO executeTask(String taskId);

    void cancelTask(String taskId);

    List<InferenceTask> schedulePendingTasks(int batchSize);

    List<InferenceTask> getDeviceTasks(String deviceKey, int limit);

    Map<String, Object> getTaskStatus(String taskId);
}
