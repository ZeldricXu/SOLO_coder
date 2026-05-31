package com.device.platform.inference;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.device.platform.common.BusinessException;
import com.device.platform.common.EntityStatus;
import com.device.platform.common.JsonUtils;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.InferenceTaskCreateRequest;
import com.device.platform.dto.InferenceTaskResultRequest;
import com.device.platform.entity.InferenceModel;
import com.device.platform.entity.InferenceTask;
import com.device.platform.mapper.InferenceModelMapper;
import com.device.platform.mapper.InferenceTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.PriorityBlockingQueue;

@Slf4j
@Service
@RequiredArgsConstructor
public class InferenceService {

    private final InferenceModelMapper inferenceModelMapper;
    private final InferenceTaskMapper inferenceTaskMapper;

    private final PriorityBlockingQueue<InferenceTask> taskQueue = new PriorityBlockingQueue<>(
            1000, Comparator.comparingInt(t -> t.getPriority() != null ? -t.getPriority() : 0));

    @Transactional
    public Mono<InferenceModel> deployModel(InferenceModel model, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            ctx.putAttribute("modelId", model.getModelId());
            ctx.putAttribute("modelName", model.getModelName());

            InferenceModel existing = inferenceModelMapper.selectOne(new LambdaQueryWrapper<InferenceModel>()
                    .eq(InferenceModel::getModelId, model.getModelId()));

            if (existing != null) {
                throw new BusinessException(400, "模型ID已存在", ctx.getTraceId());
            }

            model.setActive(true);
            model.setDeployedAt(Instant.now());
            inferenceModelMapper.insert(model);

            log.info("AI模型部署成功: modelId={}, name={}, version={}, traceId={}",
                    model.getModelId(), model.getModelName(), model.getModelVersion(), ctx.getTraceId());

            return model;
        });
    }

    public Mono<List<InferenceModel>> listModels(String modelType, TraceContext ctx) {
        return Mono.fromCallable(() -> inferenceModelMapper.selectList(new LambdaQueryWrapper<InferenceModel>()
                .eq(modelType != null, InferenceModel::getModelType, modelType)
                .eq(InferenceModel::isActive, true)
                .orderByDesc(InferenceModel::getDeployedAt)));
    }

    @Transactional
    public Mono<InferenceTask> createTask(InferenceTaskCreateRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            ctx.putAttribute("modelId", request.getModelId());
            ctx.putAttribute("deviceId", request.getDeviceId());

            InferenceModel model = inferenceModelMapper.selectOne(new LambdaQueryWrapper<InferenceModel>()
                    .eq(InferenceModel::getModelId, request.getModelId())
                    .eq(InferenceModel::isActive, true));

            if (model == null) {
                throw new BusinessException(404, "模型不存在或未激活", ctx.getTraceId());
            }

            InferenceTask task = new InferenceTask();
            task.setTaskId(generateTaskId());
            task.setModelId(request.getModelId());
            task.setDeviceId(request.getDeviceId());
            task.setStatus(EntityStatus.PENDING);
            task.setInputData(JsonUtils.toJson(request.getInputData()));
            task.setPriority(request.getPriority() != null ? request.getPriority() : 0);
            task.setScheduledAt(Instant.now());

            inferenceTaskMapper.insert(task);
            taskQueue.offer(task);

            log.info("推理任务已创建: taskId={}, modelId={}, deviceId={}, priority={}, traceId={}",
                    task.getTaskId(), request.getModelId(), request.getDeviceId(),
                    task.getPriority(), ctx.getTraceId());

            return task;
        });
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void processTaskQueue() {
        while (!taskQueue.isEmpty()) {
            InferenceTask task = taskQueue.poll();
            if (task == null) break;

            try {
                inferenceTaskMapper.update(null, new LambdaUpdateWrapper<InferenceTask>()
                        .eq(InferenceTask::getId, task.getId())
                        .set(InferenceTask::getStatus, EntityStatus.PROCESSING)
                        .set(InferenceTask::getStartedAt, Instant.now()));

                log.debug("开始处理推理任务: taskId={}", task.getTaskId());

                scheduleTaskToDevice(task);

            } catch (Exception e) {
                handleTaskError(task, e);
            }
        }
    }

    protected void scheduleTaskToDevice(InferenceTask task) {
        log.info("推理任务已调度到边缘设备: taskId={}, deviceId={}",
                task.getTaskId(), task.getDeviceId());
    }

    @Transactional
    public Mono<InferenceTask> reportTaskResult(InferenceTaskResultRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            InferenceTask task = inferenceTaskMapper.selectOne(new LambdaQueryWrapper<InferenceTask>()
                    .eq(InferenceTask::getTaskId, request.getTaskId()));

            if (task == null) {
                throw new BusinessException(404, "推理任务不存在", ctx.getTraceId());
            }

            if (request.getErrorDetail() != null && !request.getErrorDetail().isEmpty()) {
                task.setStatus(EntityStatus.FAILED);
                task.setErrorDetail(request.getErrorDetail());
                log.warn("推理任务失败: taskId={}, error={}, traceId={}",
                        request.getTaskId(), request.getErrorDetail(), ctx.getTraceId());
            } else {
                task.setStatus(EntityStatus.SUCCESS);
                task.setOutputData(JsonUtils.toJson(request.getOutputData()));
                task.setConfidence(request.getConfidence());
                task.setInferenceTimeMs(request.getInferenceTimeMs());
                log.info("推理任务完成: taskId={}, confidence={}, time={}ms, traceId={}",
                        request.getTaskId(), request.getConfidence(),
                        request.getInferenceTimeMs(), ctx.getTraceId());
            }

            task.setCompletedAt(Instant.now());
            inferenceTaskMapper.updateById(task);

            return task;
        });
    }

    private void handleTaskError(InferenceTask task, Exception e) {
        try {
            inferenceTaskMapper.update(null, new LambdaUpdateWrapper<InferenceTask>()
                    .eq(InferenceTask::getId, task.getId())
                    .set(InferenceTask::getStatus, EntityStatus.FAILED)
                    .set(InferenceTask::getErrorDetail, e.getMessage())
                    .set(InferenceTask::getCompletedAt, Instant.now()));

            log.error("推理任务处理失败: taskId={}, error={}", task.getTaskId(), e.getMessage(), e);
        } catch (Exception ex) {
            log.error("更新任务失败状态出错: {}", ex.getMessage());
        }
    }

    public Mono<InferenceTask> getTaskStatus(String taskId, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            InferenceTask task = inferenceTaskMapper.selectOne(new LambdaQueryWrapper<InferenceTask>()
                    .eq(InferenceTask::getTaskId, taskId));

            if (task == null) {
                throw new BusinessException(404, "推理任务不存在", ctx.getTraceId());
            }

            return task;
        });
    }

    public Flux<InferenceTask> listDeviceTasks(String deviceId, EntityStatus status, TraceContext ctx) {
        return Flux.fromIterable(inferenceTaskMapper.selectList(new LambdaQueryWrapper<InferenceTask>()
                .eq(InferenceTask::getDeviceId, deviceId)
                .eq(status != null, InferenceTask::getStatus, status)
                .orderByDesc(InferenceTask::getCreatedAt)
                .last("LIMIT 100")));
    }

    @Transactional
    public Mono<Void> undeployModel(String modelId, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            InferenceModel model = inferenceModelMapper.selectOne(new LambdaQueryWrapper<InferenceModel>()
                    .eq(InferenceModel::getModelId, modelId));

            if (model != null) {
                model.setActive(false);
                inferenceModelMapper.updateById(model);
                log.info("模型已下架: modelId={}, traceId={}", modelId, ctx.getTraceId());
            }

            return null;
        });
    }

    private String generateTaskId() {
        return "inf_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}
