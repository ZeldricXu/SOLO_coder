package com.tsdbproxy.vector.index.spi;

import com.tsdbproxy.vector.index.model.VectorDocument;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public interface VectorBatchStore {

    Mono<Void> batchSave(Map<Long, List<VectorDocument>> documentsByIndex);

    Mono<Map<Long, List<VectorDocument>>> batchLoad(List<Long> indexIds);

    Mono<Void> batchDelete(List<Long> indexIds);
}
