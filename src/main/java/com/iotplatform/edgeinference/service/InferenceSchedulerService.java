package com.iotplatform.edgeinference.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iotplatform.edgeinference.dto.InferenceTaskCreateDTO;
import com.iotplatform.edgeinference.dto.InferenceResultDTO;
import com.iotplatform.edgeinference.entity.InferenceTask;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.List;

public interface InferenceSchedulerService {

    Mono<InferenceTask> createTask(InferenceTaskCreateDTO dto);

    Mono<InferenceTask> getTask(String taskId);

    Mono<IPage<InferenceTask>> listTasks(String deviceId, String modelId, String status,
                                         Integer pageNum, Integer pageSize);

    Mono<List<InferenceTask>> getPendingTasks(int limit);

    Mono<Void> startTask(String taskId);

    Mono<Void> updateProgress(String taskId, double progress);

    Mono<Void> completeTask(InferenceResultDTO result);

    Mono<Void> failTask(String taskId, String errorDetail);

    Mono<Void> cancelTask(String taskId);

    Flux<InferenceTask> scheduleTasks();

    Mono<List<InferenceTask>> getDeviceTasks(String deviceId);
}
