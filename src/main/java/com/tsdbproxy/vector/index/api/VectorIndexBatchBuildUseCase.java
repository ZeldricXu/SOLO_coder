package com.tsdbproxy.vector.index.api;

import com.tsdbproxy.vector.index.model.IndexConfig;
import com.tsdbproxy.vector.index.model.IndexStats;
import com.tsdbproxy.vector.index.model.VectorDocument;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public interface VectorIndexBatchBuildUseCase {

    Mono<Map<Long, IndexStats>> batchBuild(List<BatchIndexBuildRequest> requests);

    Mono<IndexStats> addDocuments(Long indexId, List<VectorDocument> documents);

    Mono<Void> removeDocuments(Long indexId, List<Long> documentIds);

    @lombok.Data
    @lombok.Builder
    class BatchIndexBuildRequest {
        private IndexConfig config;
        private List<VectorDocument> documents;
    }
}
