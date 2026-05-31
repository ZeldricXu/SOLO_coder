package com.modelguard.service;

import com.modelguard.common.PageResult;
import com.modelguard.dto.ChunkCreateDTO;
import com.modelguard.dto.DocumentPipelineDTO;
import com.modelguard.dto.DocumentTaskDTO;
import com.modelguard.entity.DocumentChunk;
import com.modelguard.entity.DocumentPipeline;
import com.modelguard.entity.DocumentTask;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface DocumentService {

    Mono<DocumentPipeline> createPipeline(DocumentPipelineDTO dto);

    Mono<DocumentPipeline> getPipeline(String pipelineId);

    Mono<PageResult<DocumentPipeline>> pagePipelines(String status, int pageNum, int pageSize);

    Mono<DocumentPipeline> updatePipeline(String pipelineId, DocumentPipelineDTO dto);

    Mono<Void> deletePipeline(String pipelineId);

    Mono<DocumentTask> submitTask(DocumentTaskDTO dto);

    Mono<DocumentTask> getTask(String taskId);

    Mono<DocumentTask> updateTaskStatus(String taskId, String status, String phase, Double progress);

    Mono<DocumentTask> markTaskFailed(String taskId, String errorDetail);

    Mono<DocumentTask> markTaskCompleted(String taskId, int totalChunks);

    Mono<PageResult<DocumentTask>> pageTasks(String pipelineId, String status, int pageNum, int pageSize);

    Mono<DocumentChunk> createChunk(ChunkCreateDTO dto);

    Mono<List<DocumentChunk>> batchCreateChunks(List<ChunkCreateDTO> chunks);

    Mono<List<DocumentChunk>> getTaskChunks(String taskId);

    Mono<PageResult<DocumentChunk>> pageTaskChunks(String taskId, int pageNum, int pageSize);

    Mono<List<String>> smartSplit(String content, int chunkSize, int chunkOverlap, String separator);

    Mono<Map<String, Object>> getTaskProgress(String taskId);

    Mono<Void> retryTask(String taskId);
}
