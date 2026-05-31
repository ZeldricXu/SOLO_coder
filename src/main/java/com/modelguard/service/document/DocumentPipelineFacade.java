package com.modelguard.service.document;

import com.modelguard.common.PageResult;
import com.modelguard.dto.request.DocumentPipelineCreateRequest;
import com.modelguard.dto.request.DocumentTaskCreateRequest;
import com.modelguard.dto.response.DocumentPipelineResponse;
import com.modelguard.dto.response.DocumentTaskResponse;
import com.modelguard.dto.response.TaskProgressResponse;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface DocumentPipelineFacade {

    Mono<DocumentPipelineResponse> createPipeline(DocumentPipelineCreateRequest request);

    Mono<DocumentPipelineResponse> getPipeline(String pipelineId);

    Mono<DocumentPipelineResponse> enablePipeline(String pipelineId);

    Mono<DocumentPipelineResponse> disablePipeline(String pipelineId);

    Mono<PageResult<DocumentPipelineResponse>> pagePipelines(String status, int pageNum, int pageSize);

    Mono<Map<String, Object>> validatePipeline(String pipelineId);

    Mono<DocumentTaskResponse> submitTask(DocumentTaskCreateRequest request);

    Mono<DocumentTaskResponse> getTask(String taskId);

    Mono<TaskProgressResponse> getTaskProgress(String taskId);

    Mono<PageResult<DocumentTaskResponse>> pageTasks(String pipelineId, String status, int pageNum, int pageSize);

    Mono<DocumentTaskResponse> markTaskCompleted(String taskId, Integer chunkCount, Integer totalTokens);

    Mono<DocumentTaskResponse> markTaskFailed(String taskId, String errorMessage);

    Mono<Boolean> cancelTask(String taskId);

    Mono<List<String>> splitDocument(String content, int chunkSize, int overlapSize);

    Mono<Map<String, Object>> parseDocument(String filePath, String fileType);

    Mono<Map<String, Object>> getPipelineStats(String pipelineId);
}
