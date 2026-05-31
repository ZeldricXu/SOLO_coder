package com.tsdbproxy.vector.index.impl.batch;

import com.tsdbproxy.vector.index.model.Neighbor;
import com.tsdbproxy.vector.index.spi.NearestNeighborIndex;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class SearchRequestBatcher {

    private final int maxBatchSize;
    private final Duration maxWaitTime;
    private final Map<Long, NearestNeighborIndex> indexCache;

    private final Map<Long, List<PendingSearch>> pendingByIndex = new ConcurrentHashMap<>();
    private final Object batchLock = new Object();
    private volatile long lastFlushTime = System.currentTimeMillis();

    public SearchRequestBatcher(int maxBatchSize, Duration maxWaitTime,
                                Map<Long, NearestNeighborIndex> indexCache) {
        this.maxBatchSize = maxBatchSize;
        this.maxWaitTime = maxWaitTime;
        this.indexCache = indexCache;
    }

    public Mono<List<Neighbor>> submit(Long indexId, float[] query, int topK) {
        PendingSearch pending = new PendingSearch(query, topK);

        synchronized (batchLock) {
            pendingByIndex.computeIfAbsent(indexId, k -> new ArrayList<>()).add(pending);

            int totalPending = pendingByIndex.values().stream().mapToInt(List::size).sum();
            long elapsed = System.currentTimeMillis() - lastFlushTime;

            if (totalPending >= maxBatchSize || elapsed >= maxWaitTime.toMillis()) {
                flushInternal();
            }
        }

        return pending.resultMono;
    }

    public void flush() {
        synchronized (batchLock) {
            flushInternal();
        }
    }

    private void flushInternal() {
        if (pendingByIndex.isEmpty()) {
            return;
        }

        log.debug("开始批量处理搜索请求, 待处理索引数={}", pendingByIndex.size());
        lastFlushTime = System.currentTimeMillis();

        Map<Long, List<PendingSearch>> toProcess = new HashMap<>(pendingByIndex);
        pendingByIndex.clear();

        Flux.fromIterable(toProcess.entrySet())
                .flatMap(entry -> {
                    Long indexId = entry.getKey();
                    List<PendingSearch> pendings = entry.getValue();
                    NearestNeighborIndex index = indexCache.get(indexId);

                    if (index == null) {
                        pendings.forEach(p -> p.sink.tryEmitError(new IllegalArgumentException("索引不存在: " + indexId)));
                        return Mono.empty();
                    }

                    return Flux.fromIterable(pendings)
                            .flatMap(pending -> Mono.fromCallable(() -> index.search(pending.query, pending.topK))
                                    .doOnSuccess(result -> pending.sink.tryEmitNext(result))
                                    .doOnError(error -> pending.sink.tryEmitError(error))
                                    .onErrorResume(e -> Mono.empty()));
                })
                .subscribe();
    }

    private static class PendingSearch {
        final float[] query;
        final int topK;
        final Sinks.One<List<Neighbor>> sink;
        final Mono<List<Neighbor>> resultMono;

        PendingSearch(float[] query, int topK) {
            this.query = query;
            this.topK = topK;
            this.sink = Sinks.one();
            this.resultMono = sink.asMono();
        }
    }
}
