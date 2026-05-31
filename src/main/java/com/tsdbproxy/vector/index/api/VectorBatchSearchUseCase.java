package com.tsdbproxy.vector.index.api;

import com.tsdbproxy.vector.index.model.Neighbor;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public interface VectorBatchSearchUseCase {

    Mono<Map<Long, List<Neighbor>>> batchSearch(List<BatchSearchRequest> requests);

    @lombok.Data
    @lombok.Builder
    class BatchSearchRequest {
        private Long indexId;
        private float[] query;
        private int topK;
    }
}
