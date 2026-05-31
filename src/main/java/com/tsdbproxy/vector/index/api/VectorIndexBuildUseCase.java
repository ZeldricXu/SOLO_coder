package com.tsdbproxy.vector.index.api;

import com.tsdbproxy.vector.index.model.IndexConfig;
import com.tsdbproxy.vector.index.model.IndexStats;
import com.tsdbproxy.vector.index.model.VectorDocument;
import reactor.core.publisher.Mono;

import java.util.List;

public interface VectorIndexBuildUseCase {

    Mono<IndexStats> build(IndexConfig config, List<VectorDocument> documents);
}
