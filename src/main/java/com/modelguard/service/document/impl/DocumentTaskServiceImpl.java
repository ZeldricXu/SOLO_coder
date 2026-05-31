package com.modelguard.service.document.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.modelguard.common.PageResult;
import com.modelguard.converter.EntityConverter;
import com.modelguard.dto.request.DocumentTaskCreateRequest;
import com.modelguard.dto.response.DocumentTaskResponse;
import com.modelguard.dto.response.TaskProgressResponse;
import com.modelguard.entity.DocumentTask;
import com.modelguard.exception.BusinessException;
import com.modelguard.exception.ResourceNotFoundException;
import com.modelguard.mapper.DocumentTaskMapper;
import com.modelguard.service.document.DocumentPipelineService;
import com.modelguard.service.document.DocumentTaskService;
import com.modelguard.util.IdGeneratorUtil;
import com.modelguard.util.ReactiveBridgeUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentTaskServiceImpl implements DocumentTaskService {

    private final DocumentTaskMapper documentTaskMapper;
    private final DocumentPipelineService documentPipelineService;

    private static final List<String> VALID_STATUSES = Arrays.asList("PENDING", "PARSING", "CHUNKING", "VECTORIZING", "COMPLETED", "FAILED", "CANCELLED");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DocumentTaskResponse> submitTask(DocumentTaskCreateRequest request) {
        return documentPipelineService.ensurePipelineActive(request.getPipelineId())
                .flatMap(pipeline -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    DocumentTask task = EntityConverter.toEntity(request);
                    task.setTaskId(IdGeneratorUtil.generateDocumentTaskId());
                    task.setStatus("PENDING");
                    task.setProgress(0);
                    task.setPipelineConfig(pipeline.getParsedPipelineConfig());
                    task.setChunkSize(pipeline.getChunkSize());
                    task.setChunkOverlap(pipeline.getChunkOverlap());

                    documentTaskMapper.insert(task);
                    log.info("Submitted document task: taskId={}, pipelineId={}", task.getTaskId(), request.getPipelineId());
                    return EntityConverter.toResponse(task);
                }));
    }

    @Override
    public Mono<DocumentTaskResponse> getTask(String taskId) {
        return getTaskEntity(taskId)
                .map(EntityConverter::toResponse);
    }

    @Override
    public Mono<DocumentTask> getTaskEntity(String taskId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<DocumentTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentTask::getTaskId, taskId);
            DocumentTask task = documentTaskMapper.selectOne(wrapper);
            if (task == null) {
                throw new ResourceNotFoundException("DocumentTask", taskId);
            }
            return task;
        });
    }

    @Override
    public Mono<TaskProgressResponse> getTaskProgress(String taskId) {
        return getTaskEntity(taskId)
                .map(task -> {
                    String status = task.getStatus();
                    Map<String, Object> progressData = task.getProgressData() != null ? task.getProgressData() : new HashMap<>();

                    int estimatedTotal = 100;
                    int currentProgress = task.getProgress() != null ? task.getProgress() : 0;

                    String stage = "initializing";
                    String stageDescription = "任务初始化中";
                    Map<String, Object> stageProgress = new HashMap<>();

                    switch (status) {
                        case "PENDING":
                            stage = "pending";
                            stageDescription = "等待处理";
                            break;
                        case "PARSING":
                            stage = "parsing";
                            stageDescription = "文档解析中";
                            stageProgress.put("pagesProcessed", progressData.get("pagesProcessed"));
                            stageProgress.put("totalPages", progressData.get("totalPages"));
                            break;
                        case "CHUNKING":
                            stage = "chunking";
                            stageDescription = "文本分块中";
                            stageProgress.put("chunksCreated", progressData.get("chunksCreated"));
                            break;
                        case "VECTORIZING":
                            stage = "vectorizing";
                            stageDescription = "向量化中";
                            stageProgress.put("vectorsCreated", progressData.get("vectorsCreated"));
                            stageProgress.put("totalChunks", progressData.get("totalChunks"));
                            break;
                        case "COMPLETED":
                            stage = "completed";
                            stageDescription = "处理完成";
                            currentProgress = 100;
                            break;
                        case "FAILED":
                            stage = "failed";
                            stageDescription = "处理失败";
                            break;
                        case "CANCELLED":
                            stage = "cancelled";
                            stageDescription = "任务已取消";
                            break;
                    }

                    return TaskProgressResponse.builder()
                            .taskId(taskId)
                            .status(status)
                            .stage(stage)
                            .stageDescription(stageDescription)
                            .progressPercent(currentProgress)
                            .estimatedTotal(estimatedTotal)
                            .processedCount(task.getChunkCount())
                            .errorMessage(task.getErrorMessage())
                            .stageProgress(stageProgress)
                            .startedAt(task.getStartedAt())
                            .completedAt(task.getCompletedAt())
                            .build();
                });
    }

    @Override
    public Mono<List<DocumentTaskResponse>> listTasksByPipeline(String pipelineId, String status) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            LambdaQueryWrapper<DocumentTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentTask::getPipelineId, pipelineId);
            if (status != null && !status.isEmpty()) {
                wrapper.eq(DocumentTask::getStatus, status);
            }
            wrapper.orderByDesc(DocumentTask::getCreatedAt);
            return documentTaskMapper.selectList(wrapper).stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());
        });
    }

    @Override
    public Mono<PageResult<DocumentTaskResponse>> pageTasksByPipeline(String pipelineId, String status, int pageNum, int pageSize) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            Page<DocumentTask> page = new Page<>(pageNum, pageSize);
            LambdaQueryWrapper<DocumentTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentTask::getPipelineId, pipelineId);
            if (status != null && !status.isEmpty()) {
                wrapper.eq(DocumentTask::getStatus, status);
            }
            wrapper.orderByDesc(DocumentTask::getCreatedAt);
            Page<DocumentTask> result = documentTaskMapper.selectPage(page, wrapper);

            List<DocumentTaskResponse> responses = result.getRecords().stream()
                    .map(EntityConverter::toResponse)
                    .collect(Collectors.toList());

            return PageResult.of(responses, result.getTotal(), pageNum, pageSize);
        });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DocumentTaskResponse> updateTaskStatus(String taskId, String status, Map<String, Object> progress) {
        if (!VALID_STATUSES.contains(status)) {
            throw new BusinessException("Invalid task status: " + status);
        }

        return getTaskEntity(taskId)
                .flatMap(task -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    if ("PARSING".equals(status) && task.getStartedAt() == null) {
                        task.setStartedAt(LocalDateTime.now());
                    }

                    task.setStatus(status);

                    if (progress != null) {
                        if (progress.get("progress") instanceof Number) {
                            task.setProgress(((Number) progress.get("progress")).intValue());
                        }
                        task.setProgressData(progress);
                    }

                    documentTaskMapper.updateById(task);
                    log.debug("Updated task status: taskId={}, status={}", taskId, status);
                    return EntityConverter.toResponse(task);
                }));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DocumentTaskResponse> markTaskCompleted(String taskId, Integer chunkCount, Integer totalTokens) {
        return getTaskEntity(taskId)
                .flatMap(task -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    task.setStatus("COMPLETED");
                    task.setProgress(100);
                    task.setChunkCount(chunkCount);
                    task.setTotalTokens(totalTokens);
                    task.setCompletedAt(LocalDateTime.now());

                    Map<String, Object> progress = new HashMap<>();
                    progress.put("chunkCount", chunkCount);
                    progress.put("totalTokens", totalTokens);
                    task.setProgressData(progress);

                    documentTaskMapper.updateById(task);
                    log.info("Completed document task: taskId={}, chunks={}, tokens={}", taskId, chunkCount, totalTokens);
                    return EntityConverter.toResponse(task);
                }));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<DocumentTaskResponse> markTaskFailed(String taskId, String errorMessage) {
        return getTaskEntity(taskId)
                .flatMap(task -> ReactiveBridgeUtil.monoFromCallable(() -> {
                    task.setStatus("FAILED");
                    task.setErrorMessage(errorMessage);
                    task.setCompletedAt(LocalDateTime.now());

                    documentTaskMapper.updateById(task);
                    log.error("Document task failed: taskId={}, error={}", taskId, errorMessage);
                    return EntityConverter.toResponse(task);
                }));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Mono<Boolean> cancelTask(String taskId) {
        return getTaskEntity(taskId)
                .flatMap(task -> {
                    if ("COMPLETED".equals(task.getStatus()) || "FAILED".equals(task.getStatus())) {
                        throw new BusinessException("Cannot cancel task in status: " + task.getStatus());
                    }
                    return ReactiveBridgeUtil.monoFromCallable(() -> {
                        task.setStatus("CANCELLED");
                        task.setCompletedAt(LocalDateTime.now());
                        documentTaskMapper.updateById(task);
                        log.info("Cancelled document task: taskId={}", taskId);
                        return true;
                    });
                });
    }

    @Override
    public Mono<Map<String, Object>> calculateTaskStats(String pipelineId) {
        return ReactiveBridgeUtil.monoFromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();

            for (String status : VALID_STATUSES) {
                LambdaQueryWrapper<DocumentTask> wrapper = new LambdaQueryWrapper<>();
                wrapper.eq(DocumentTask::getPipelineId, pipelineId)
                        .eq(DocumentTask::getStatus, status);
                Long count = documentTaskMapper.selectCount(wrapper);
                stats.put(status.toLowerCase() + "_count", count);
            }

            LambdaQueryWrapper<DocumentTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DocumentTask::getPipelineId, pipelineId);
            Long totalTasks = documentTaskMapper.selectCount(wrapper);
            stats.put("total_tasks", totalTasks);

            return stats;
        });
    }
}
