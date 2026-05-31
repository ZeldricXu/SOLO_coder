package com.tsdbproxy.vector.index.app;

import com.tsdbproxy.vector.index.api.VectorBatchSearchUseCase;
import com.tsdbproxy.vector.index.api.VectorIndexBatchBuildUseCase;
import com.tsdbproxy.vector.index.api.VectorIndexBuildUseCase;
import com.tsdbproxy.vector.index.api.VectorSearchUseCase;
import com.tsdbproxy.vector.index.impl.batch.SearchRequestBatcher;
import com.tsdbproxy.vector.index.model.IndexConfig;
import com.tsdbproxy.vector.index.model.IndexStats;
import com.tsdbproxy.vector.index.model.Neighbor;
import com.tsdbproxy.vector.index.model.VectorDocument;
import com.tsdbproxy.vector.index.spi.IndexRepository;
import com.tsdbproxy.vector.index.spi.NearestNeighborIndex;
import com.tsdbproxy.vector.index.spi.VectorBatchStore;
import com.tsdbproxy.vector.index.spi.VectorStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class VectorIndexService implements VectorIndexBuildUseCase, VectorSearchUseCase,
        VectorIndexBatchBuildUseCase, VectorBatchSearchUseCase {

    private final IndexRepository indexRepository;
    private final NearestNeighborIndex nearestNeighborIndex;
    private final VectorStore vectorStore;
    private final VectorBatchStore vectorBatchStore;
    private final SearchRequestBatcher searchRequestBatcher;
    private final MeterRegistry meterRegistry;

    private final Map<Long, NearestNeighborIndex> indexCache;

    private final Counter buildTotalCounter;
    private final Counter buildSuccessCounter;
    private final Counter searchTotalCounter;
    private final Counter searchBatchedCounter;
    private final Timer buildLatencyTimer;
    private final Timer searchLatencyTimer;

    @org.springframework.beans.factory.annotation.Autowired
    public VectorIndexService(
            IndexRepository indexRepository,
            NearestNeighborIndex nearestNeighborIndex,
            VectorStore vectorStore,
            VectorBatchStore vectorBatchStore,
            Map<Long, NearestNeighborIndex> indexCache,
            MeterRegistry meterRegistry,
            @org.springframework.beans.factory.annotation.Value("${vector.batch.search.max-batch-size:100}") int maxBatchSize,
            @org.springframework.beans.factory.annotation.Value("${vector.batch.search.max-wait-ms:100}") int maxWaitMs) {
        this.indexRepository = indexRepository;
        this.nearestNeighborIndex = nearestNeighborIndex;
        this.vectorStore = vectorStore;
        this.vectorBatchStore = vectorBatchStore;
        this.indexCache = indexCache;
        this.meterRegistry = meterRegistry;
        this.searchRequestBatcher = new SearchRequestBatcher(maxBatchSize, java.time.Duration.ofMillis(maxWaitMs), indexCache);

        this.buildTotalCounter = Counter.builder("vector.index.build.total")
                .description("总构建次数")
                .register(meterRegistry);
        this.buildSuccessCounter = Counter.builder("vector.index.build.success")
                .description("成功构建次数")
                .register(meterRegistry);
        this.searchTotalCounter = Counter.builder("vector.index.search.total")
                .description("总搜索次数")
                .register(meterRegistry);
        this.searchBatchedCounter = Counter.builder("vector.index.search.batched")
                .description("批量搜索次数")
                .register(meterRegistry);
        this.buildLatencyTimer = Timer.builder("vector.index.build.latency")
                .description("构建延迟")
                .register(meterRegistry);
        this.searchLatencyTimer = Timer.builder("vector.index.search.latency")
                .description("搜索延迟")
                .register(meterRegistry);
    }

    @Override
    public Mono<IndexStats> build(IndexConfig config, List<VectorDocument> documents) {
        buildTotalCounter.increment();
        Timer.Sample sample = Timer.start();

        return Mono.fromCallable(() -> {
            log.info("开始构建向量索引: {}", config.getName());

            Long indexId = indexRepository.create(config);

            NearestNeighborIndex index = nearestNeighborIndex;
            index.build(documents);
            indexCache.put(indexId, index);

            vectorStore.save(indexId, documents);
            indexRepository.updateStatus(indexId, "ready", index.size());

            buildSuccessCounter.increment();
            sample.stop(buildLatencyTimer);
            log.info("向量索引构建完成: id={}, size={}", indexId, index.size());
            return indexRepository.getStats(indexId);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<Neighbor>> search(Long indexId, float[] query, int topK) {
        searchTotalCounter.increment();
        Timer.Sample sample = Timer.start();

        return searchRequestBatcher.submit(indexId, query, topK)
                .doOnNext(result -> {
                    sample.stop(searchLatencyTimer);
                    log.debug("向量搜索完成: indexId={}, topK={}", indexId, topK);
                });
    }

    @Override
    public Mono<Map<Long, IndexStats>> batchBuild(List<VectorIndexBatchBuildUseCase.BatchIndexBuildRequest> requests) {
        log.info("开始批量构建向量索引, 数量={}", requests.size());
        buildTotalCounter.increment(requests.size());

        Map<Long, List<VectorDocument>> docsByIndex = new HashMap<>();
        Map<Long, NearestNeighborIndex> builtIndexes = new HashMap<>();
        Map<Long, IndexConfig> configs = new HashMap<>();

        return Flux.fromIterable(requests)
                .flatMap(request -> Mono.fromCallable(() -> {
                    Long indexId = indexRepository.create(request.getConfig());
                    NearestNeighborIndex index = new com.tsdbproxy.vector.index.impl.HnswNearestNeighborIndex();
                    index.build(request.getDocuments());

                    docsByIndex.put(indexId, request.getDocuments());
                    builtIndexes.put(indexId, index);
                    configs.put(indexId, request.getConfig());
                    indexRepository.updateStatus(indexId, "ready", index.size());
                    indexCache.put(indexId, index);

                    return indexId;
                }).subscribeOn(Schedulers.boundedElastic()))
                .collectList()
                .flatMap(indexIds -> vectorBatchStore.batchSave(docsByIndex)
                        .thenReturn(indexIds))
                .flatMap(indexIds -> {
                    Map<Long, IndexStats> stats = new HashMap<>();
                    for (Long indexId : indexIds) {
                        stats.put(indexId, indexRepository.getStats(indexId));
                    }
                    buildSuccessCounter.increment(requests.size());
                    log.info("批量构建向量索引完成, 数量={}", requests.size());
                    return Mono.just(stats);
                });
    }

    @Override
    public Mono<IndexStats> addDocuments(Long indexId, List<VectorDocument> documents) {
        log.info("向索引添加文档: indexId={}, count={}", indexId, documents.size());

        return Mono.fromCallable(() -> {
            NearestNeighborIndex index = getIndex(indexId);
            if (index == null) {
                throw new IllegalArgumentException("索引不存在: " + indexId);
            }

            documents.forEach(doc -> index.add(doc.getId(), doc.getVector()));

            List<VectorDocument> existingDocs = vectorStore.load(indexId);
            if (existingDocs != null) {
                existingDocs.addAll(documents);
                vectorStore.save(indexId, existingDocs);
            }

            indexRepository.updateStatus(indexId, "ready", index.size());
            return indexRepository.getStats(indexId);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> removeDocuments(Long indexId, List<Long> documentIds) {
        log.info("从索引删除文档: indexId={}, count={}", indexId, documentIds.size());

        return Mono.fromRunnable(() -> {
            NearestNeighborIndex index = getIndex(indexId);
            if (index == null) {
                return;
            }

            documentIds.forEach(index::remove);

            List<VectorDocument> existingDocs = vectorStore.load(indexId);
            if (existingDocs != null) {
                existingDocs.removeIf(doc -> documentIds.contains(doc.getId()));
                vectorStore.save(indexId, existingDocs);
            }

            indexRepository.updateStatus(indexId, "ready", index.size());
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    public Mono<Map<Long, List<Neighbor>>> batchSearch(List<VectorBatchSearchUseCase.BatchSearchRequest> requests) {
        log.debug("批量向量搜索, 请求数={}", requests.size());
        searchBatchedCounter.increment();

        Map<Long, List<Neighbor>> results = new HashMap<>();

        return Flux.fromIterable(requests)
                .flatMap(request -> search(request.getIndexId(), request.getQuery(), request.getTopK())
                        .map(neighbors -> {
                            results.put(request.getIndexId(), neighbors);
                            return request.getIndexId();
                        }))
                .collectList()
                .thenReturn(results);
    }

    private NearestNeighborIndex getIndex(Long indexId) {
        NearestNeighborIndex index = indexCache.get(indexId);
        if (index != null) {
            return index;
        }

        List<VectorDocument> documents = vectorStore.load(indexId);
        if (documents == null) {
            return null;
        }

        index = new com.tsdbproxy.vector.index.impl.HnswNearestNeighborIndex();
        index.build(documents);
        indexCache.put(indexId, index);
        return index;
    }
}
