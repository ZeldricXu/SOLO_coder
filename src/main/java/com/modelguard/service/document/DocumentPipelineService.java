package com.modelguard.service.document;

import com.modelguard.common.PageResult;
import com.modelguard.dto.request.DocumentPipelineCreateRequest;
import com.modelguard.dto.response.DocumentPipelineResponse;
import com.modelguard.entity.DocumentPipeline;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface DocumentPipelineService {

    Mono<DocumentPipelineResponse> createPipeline(DocumentPipelineCreateRequest request);

    Mono<DocumentPipelineResponse> getPipeline(String pipelineId);

    Mono<DocumentPipeline> getPipelineEntity(String pipelineId);

    Mono<DocumentPipelineResponse> updatePipeline(String pipelineId, Map<String, Object> updates);

    Mono<Boolean> deletePipeline(String pipelineId);

    Mono<List<DocumentPipelineResponse>> listPipelines(String status);

    Mono<PageResult<DocumentPipelineResponse>> pagePipelines(String status, int pageNum, int pageSize);

    Mono<DocumentPipelineResponse> enablePipeline(String pipelineId);

    Mono<DocumentPipelineResponse> disablePipeline(String pipelineId);

    Mono<Map<String, Object>> validatePipelineConfig(String pipelineId);

    Mono<DocumentPipeline> ensurePipelineActive(String pipelineId);
}
