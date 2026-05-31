package com.modelguard.service.document;

import com.modelguard.common.PageResult;
import com.modelguard.dto.request.DocumentChunkCreateRequest;
import com.modelguard.dto.response.DocumentChunkResponse;
import reactor.core.publisher.Mono;
import java.util.List;

public interface DocumentChunkService {

    Mono<DocumentChunkResponse> createChunk(DocumentChunkCreateRequest request);

    Mono<List<DocumentChunkResponse>> createChunks(List<DocumentChunkCreateRequest> requests);

    Mono<DocumentChunkResponse> getChunk(String chunkId);

    Mono<List<DocumentChunkResponse>> listChunksByTask(String taskId);

    Mono<PageResult<DocumentChunkResponse>> pageChunksByTask(String taskId, int pageNum, int pageSize);

    Mono<List<DocumentChunkResponse>> searchChunks(String pipelineId, String keyword);

    Mono<Boolean> deleteChunksByTask(String taskId);

    Mono<Integer> countChunksByTask(String taskId);

    Mono<List<String>> smartSplitDocument(String content, int chunkSize, int overlapSize);
}
