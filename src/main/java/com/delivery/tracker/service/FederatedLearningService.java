package com.delivery.tracker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delivery.tracker.entity.FLTrainingTask;
import com.delivery.tracker.mapper.FLTrainingTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class FederatedLearningService {

    private final FLTrainingTaskMapper trainingTaskMapper;
    private final Map<String, Map<String, double[]>> gradientStore = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();

    public Mono<FLTrainingTask> createTrainingTask(String modelName, int totalRounds, List<String> participants) {
        return Mono.fromCallable(() -> {
            String taskId = "fl_task_" + UUID.randomUUID().toString().substring(0, 8);

            FLTrainingTask task = new FLTrainingTask();
            task.setTaskId(taskId);
            task.setModelName(modelName);
            task.setStatus("CREATED");
            task.setCurrentRound(0);
            task.setTotalRounds(totalRounds);
            task.setParticipants(participants);
            task.setGlobalModelPath("/models/" + modelName + "_" + taskId + ".pt");
            trainingTaskMapper.insert(task);

            log.info("联邦学习任务创建成功: taskId={}, modelName={}", taskId, modelName);
            return task;
        });
    }

    public Mono<FLTrainingTask> startTraining(String taskId) {
        return getTask(taskId)
                .switchIfEmpty(Mono.error(new RuntimeException("任务不存在: " + taskId)))
                .doOnNext(task -> {
                    task.setStatus("RUNNING");
                    task.setStartedAt(LocalDateTime.now());
                    task.setCurrentRound(1);
                    trainingTaskMapper.updateById(task);
                    gradientStore.put(taskId, new ConcurrentHashMap<>());
                    log.info("联邦学习任务开始: taskId={}, round={}", taskId, task.getCurrentRound());
                });
    }

    public Mono<Map<String, Object>> submitGradient(String taskId, String participantId, double[] gradients) {
        return getTask(taskId)
                .switchIfEmpty(Mono.error(new RuntimeException("任务不存在: " + taskId)))
                .filter(task -> "RUNNING".equals(task.getStatus()))
                .switchIfEmpty(Mono.error(new RuntimeException("任务未在运行中")))
                .map(task -> {
                    double[] encryptedGradients = encryptGradients(gradients);
                    gradientStore.get(taskId).put(participantId, encryptedGradients);
                    log.debug("梯度已提交: taskId={}, participant={}", taskId, participantId);

                    return Map.of(
                            "taskId", taskId,
                            "participant", participantId,
                            "round", task.getCurrentRound(),
                            "received", true
                    );
                });
    }

    public Mono<Map<String, Object>> aggregateAndUpdate(String taskId) {
        return getTask(taskId)
                .switchIfEmpty(Mono.error(new RuntimeException("任务不存在: " + taskId)))
                .filter(task -> "RUNNING".equals(task.getStatus()))
                .switchIfEmpty(Mono.error(new RuntimeException("任务未在运行中")))
                .map(task -> {
                    Map<String, double[]> participantGradients = gradientStore.get(taskId);
                    if (participantGradients == null || participantGradients.isEmpty()) {
                        throw new RuntimeException("没有可用的梯度进行聚合");
                    }

                    double[] aggregatedGradients = aggregateGradients(participantGradients);
                    double[] globalModel = updateGlobalModel(taskId, aggregatedGradients);

                    int nextRound = task.getCurrentRound() + 1;
                    if (nextRound > task.getTotalRounds()) {
                        task.setStatus("COMPLETED");
                        task.setCompletedAt(LocalDateTime.now());
                        log.info("联邦学习任务完成: taskId={}", taskId);
                    } else {
                        task.setCurrentRound(nextRound);
                        gradientStore.put(taskId, new ConcurrentHashMap<>());
                        log.info("联邦学习轮次完成: taskId={}, nextRound={}", taskId, nextRound);
                    }
                    trainingTaskMapper.updateById(task);

                    return Map.of(
                            "taskId", taskId,
                            "status", task.getStatus(),
                            "currentRound", task.getCurrentRound(),
                            "totalRounds", task.getTotalRounds(),
                            "modelUpdated", true
                    );
                });
    }

    public Mono<FLTrainingTask> getTask(String taskId) {
        return Mono.fromCallable(() ->
                trainingTaskMapper.selectOne(
                        new LambdaQueryWrapper<FLTrainingTask>()
                                .eq(FLTrainingTask::getTaskId, taskId)
                )
        );
    }

    public Flux<FLTrainingTask> getAllTasks() {
        return Flux.fromIterable(trainingTaskMapper.selectList(null));
    }

    public Mono<double[]> getGlobalModel(String taskId) {
        return getTask(taskId)
                .switchIfEmpty(Mono.error(new RuntimeException("任务不存在: " + taskId)))
                .map(task -> {
                    double[] model = new double[100];
                    for (int i = 0; i < model.length; i++) {
                        model[i] = secureRandom.nextGaussian() * 0.01;
                    }
                    return model;
                });
    }

    private double[] encryptGradients(double[] gradients) {
        double[] encrypted = new double[gradients.length];
        for (int i = 0; i < gradients.length; i++) {
            encrypted[i] = gradients[i] + secureRandom.nextGaussian() * 0.001;
        }
        return encrypted;
    }

    private double[] aggregateGradients(Map<String, double[]> participantGradients) {
        if (participantGradients.isEmpty()) {
            return new double[0];
        }

        int length = participantGradients.values().iterator().next().length;
        double[] aggregated = new double[length];
        int count = 0;

        for (double[] gradients : participantGradients.values()) {
            for (int i = 0; i < length; i++) {
                aggregated[i] += gradients[i];
            }
            count++;
        }

        for (int i = 0; i < length; i++) {
            aggregated[i] /= count;
        }

        log.debug("梯度聚合完成，参与方数量: {}", count);
        return aggregated;
    }

    private double[] updateGlobalModel(String taskId, double[] gradients) {
        double learningRate = 0.01;
        double[] model = new double[gradients.length];
        for (int i = 0; i < model.length; i++) {
            model[i] = gradients[i] * learningRate;
        }
        return model;
    }

    public Mono<Void> cancelTask(String taskId) {
        return Mono.fromRunnable(() -> {
            FLTrainingTask task = trainingTaskMapper.selectOne(
                    new LambdaQueryWrapper<FLTrainingTask>()
                            .eq(FLTrainingTask::getTaskId, taskId)
            );
            if (task != null) {
                task.setStatus("CANCELLED");
                trainingTaskMapper.updateById(task);
                gradientStore.remove(taskId);
                log.info("联邦学习任务已取消: taskId={}", taskId);
            }
        });
    }
}
