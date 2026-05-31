package com.tsdbproxy.vector.index.api;

import com.tsdbproxy.vector.index.model.Neighbor;
import reactor.core.publisher.Mono;

import java.util.List;

public interface VectorSearchUseCase {

    Mono<List<Neighbor>> search(Long indexId, float[] query, int topK);
}
