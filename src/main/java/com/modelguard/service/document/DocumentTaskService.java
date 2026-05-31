package com.modelguard.service.document;

import com.modelguard.common.PageResult;
import com.modelguard.dto.request.DocumentTaskCreateRequest;
import com.modelguard.dto.response.DocumentTaskResponse;
import com.modelguard.dto.response.TaskProgressResponse;
import com.modelguard.entity.DocumentTask;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface DocumentTaskService {

    Mono<DocumentTaskResponse> submitTask(DocumentTaskCreateRequest request);

    Mono<DocumentTaskResponse> getTask(String taskId);

    Mono<DocumentTask> getTaskEntity(String taskId);

    Mono<TaskProgressResponse> getTaskProgress(String taskId);

    Mono<List<DocumentTaskResponse>> listTasksByPipeline(String pipelineId, String status);

    Mono<PageResult<DocumentTaskResponse>> pageTasksByPipeline(String pipelineId, String status, int pageNum, int pageSize);

    Mono<DocumentTaskResponse> updateTaskStatus(String taskId, String status, Map<String, Object> progress);

    Mono<DocumentTaskResponse> markTaskCompleted(String taskId, Integer chunkCount, Integer totalTokens);

    Mono<DocumentTaskResponse> markTaskFailed(String taskId, String errorMessage);

    Mono<Boolean> cancelTask(String taskId);

    Mono<Map<String, Object>> calculateTaskStats(String pipelineId);
}
