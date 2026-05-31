package com.iotplatform.edgeinference.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iotplatform.common.exception.BusinessException;
import com.iotplatform.edgeinference.dto.InferenceTaskCreateDTO;
import com.iotplatform.edgeinference.dto.InferenceResultDTO;
import com.iotplatform.edgeinference.entity.InferenceTask;
import com.iotplatform.edgeinference.mapper.InferenceTaskMapper;
import com.iotplatform.edgeinference.service.InferenceSchedulerService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveListOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InferenceSchedulerServiceImpl implements InferenceSchedulerService {

    private final InferenceTaskMapper taskMapper;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    private static final String TASK_QUEUE_KEY = "inference:task:queue";
    private static final String TASK_RUNNING_KEY = "inference:task:running";
    private static final int MAX_CONCURRENT_TASKS = 10;

    @Override
    @Transactional
    public Mono<InferenceTask> createTask(InferenceTaskCreateDTO dto) {
        Timer.Sample sample = Timer.start(meterRegistry);
        return Mono.fromCallable(() -> {
            try {
                InferenceTask task = new InferenceTask();
                task.setTaskId("task_" + IdUtil.getSnowflakeNextIdStr());
                task.setDeviceId(dto.getDeviceId());
                task.setModelId(dto.getModelId());
                task.setModelVersion(dto.getModelVersion());
                task.setInputData(dto.getInputData());
                task.setInputPath(dto.getInputPath());
                task.setStatus(InferenceTask.Status.PENDING);
                task.setProgress(BigDecimal.ZERO);
                task.setPriority(dto.getPriority() != null ? dto.getPriority() : 5);

                taskMapper.insert(task);

                ReactiveListOperations<String, String> listOps = redisTemplate.opsForList();
                listOps.rightPush(TASK_QUEUE_KEY, task.getTaskId()).subscribe();

                log.info("Inference task created: {}", task.getTaskId());
                meterRegistry.counter("inference.task.created").increment();
                return task;
            } catch (Exception e) {
                log.error("Failed to create inference task: {}", e.getMessage(), e);
                meterRegistry.counter("inference.task.create.failed").increment();
                throw new BusinessException("创建推理任务失败: " + e.getMessage());
            } finally {
                sample.stop(meterRegistry.timer("inference.task.create.latency"));
            }
        });
    }

    @Override
    public Mono<InferenceTask> getTask(String taskId) {
        return Mono.fromCallable(() -> taskMapper.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(404, "任务不存在: " + taskId)));
    }

    @Override
    public Mono<IPage<InferenceTask>> listTasks(String deviceId, String modelId, String status,
                                                Integer pageNum, Integer pageSize) {
        return Mono.fromCallable(() -> {
            Page<InferenceTask> page = new Page<>(pageNum, pageSize);
            return taskMapper.selectTaskPage(page, deviceId, modelId, status);
        });
    }

    @Override
    public Mono<List<InferenceTask>> getPendingTasks(int limit) {
        return Mono.fromCallable(() -> taskMapper.findPendingTasks(limit));
    }

    @Override
    @Transactional
    public Mono<Void> startTask(String taskId) {
        return Mono.fromCallable(() -> {
            InferenceTask task = taskMapper.findByTaskId(taskId)
                    .orElseThrow(() -> new BusinessException(404, "任务不存在: " + taskId));

            if (!InferenceTask.Status.PENDING.equals(task.getStatus())) {
                throw new BusinessException(400, "任务状态不允许启动: " + task.getStatus());
            }

            task.setStatus(InferenceTask.Status.PROCESSING);
            task.setStartedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            redisTemplate.opsForSet().add(TASK_RUNNING_KEY, taskId).subscribe();

            log.info("Inference task started: {}", taskId);
            meterRegistry.counter("inference.task.started").increment();
            return null;
        });
    }

    @Override
    @Transactional
    public Mono<Void> updateProgress(String taskId, double progress) {
        return Mono.fromCallable(() -> {
            BigDecimal progressDecimal = BigDecimal.valueOf(Math.min(100, Math.max(0, progress)));
            int updated = taskMapper.updateStatus(taskId, InferenceTask.Status.PROCESSING, progressDecimal);
            if (updated == 0) {
                throw new BusinessException(404, "任务不存在: " + taskId);
            }
            log.debug("Inference task progress updated: {} - {}%", taskId, progress);
            return null;
        });
    }

    @Override
    @Transactional
    public Mono<Void> completeTask(InferenceResultDTO result) {
        return Mono.fromCallable(() -> {
            InferenceTask task = taskMapper.findByTaskId(result.getTaskId())
                    .orElseThrow(() -> new BusinessException(404, "任务不存在: " + result.getTaskId()));

            LocalDateTime now = LocalDateTime.now();
            int updated = taskMapper.completeTask(
                    result.getTaskId(),
                    result.getOutputData(),
                    result.getOutputPath(),
                    now
            );

            if (updated == 0) {
                throw new BusinessException("更新任务状态失败");
            }

            redisTemplate.opsForSet().remove(TASK_RUNNING_KEY, result.getTaskId()).subscribe();

            log.info("Inference task completed: {}", result.getTaskId());
            meterRegistry.counter("inference.task.completed").increment();

            if (result.getInferenceTimeMs() != null) {
                meterRegistry.timer("inference.task.duration")
                        .record(result.getInferenceTimeMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
            }
            return null;
        });
    }

    @Override
    @Transactional
    public Mono<Void> failTask(String taskId, String errorDetail) {
        return Mono.fromCallable(() -> {
            int updated = taskMapper.failTask(taskId, errorDetail, LocalDateTime.now());
            if (updated == 0) {
                throw new BusinessException(404, "任务不存在: " + taskId);
            }

            redisTemplate.opsForSet().remove(TASK_RUNNING_KEY, taskId).subscribe();

            log.error("Inference task failed: {} - {}", taskId, errorDetail);
            meterRegistry.counter("inference.task.failed").increment();
            return null;
        });
    }

    @Override
    @Transactional
    public Mono<Void> cancelTask(String taskId) {
        return Mono.fromCallable(() -> {
            InferenceTask task = taskMapper.findByTaskId(taskId)
                    .orElseThrow(() -> new BusinessException(404, "任务不存在: " + taskId));

            if (InferenceTask.Status.PROCESSING.equals(task.getStatus())) {
                throw new BusinessException(400, "处理中的任务无法取消");
            }

            task.setStatus(InferenceTask.Status.CANCELLED);
            task.setCompletedAt(LocalDateTime.now());
            taskMapper.updateById(task);

            log.info("Inference task cancelled: {}", taskId);
            return null;
        });
    }

    @Override
    public Flux<InferenceTask> scheduleTasks() {
        return getPendingTasks(MAX_CONCURRENT_TASKS)
                .flatMapMany(Flux::fromIterable)
                .flatMap(task -> startTask(task.getTaskId()).thenReturn(task))
                .doOnNext(task -> log.info("Task scheduled: {}", task.getTaskId()));
    }

    @Override
    public Mono<List<InferenceTask>> getDeviceTasks(String deviceId) {
        return Mono.fromCallable(() -> taskMapper.findByDeviceId(deviceId));
    }

    @Scheduled(fixedRate = 5000)
    public void schedulePendingTasks() {
        redisTemplate.opsForSet().size(TASK_RUNNING_KEY)
                .filter(runningCount -> runningCount < MAX_CONCURRENT_TASKS)
                .flatMap(runningCount -> {
                    int availableSlots = MAX_CONCURRENT_TASKS - runningCount.intValue();
                    return redisTemplate.opsForList().leftPop(TASK_QUEUE_KEY, availableSlots);
                })
                .flatMap(taskIds -> Flux.fromIterable(taskIds)
                        .flatMap(this::startTask)
                        .then())
                .subscribe();
    }
}
