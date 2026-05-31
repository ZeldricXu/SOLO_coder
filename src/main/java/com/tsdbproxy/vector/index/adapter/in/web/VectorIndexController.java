package com.tsdbproxy.vector.index.adapter.in.web;

import com.tsdbproxy.common.result.Result;
import com.tsdbproxy.vector.index.api.VectorBatchSearchUseCase;
import com.tsdbproxy.vector.index.api.VectorIndexBatchBuildUseCase;
import com.tsdbproxy.vector.index.api.VectorIndexBuildUseCase;
import com.tsdbproxy.vector.index.api.VectorSearchUseCase;
import com.tsdbproxy.vector.index.model.IndexConfig;
import com.tsdbproxy.vector.index.model.IndexStats;
import com.tsdbproxy.vector.index.model.Neighbor;
import com.tsdbproxy.vector.index.model.VectorDocument;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@RestController
@RequestMapping("/api/v1/vector")
@RequiredArgsConstructor
public class VectorIndexController {

    private final VectorIndexBuildUseCase buildUseCase;
    private final VectorSearchUseCase searchUseCase;
    private final VectorIndexBatchBuildUseCase batchBuildUseCase;
    private final VectorBatchSearchUseCase batchSearchUseCase;

    @PostMapping("/build")
    public Mono<Result<IndexStats>> build(@RequestBody BuildWebRequest request) {
        IndexConfig config = IndexConfig.builder()
                .name(request.getIndexName())
                .dimension(request.getDimension())
                .metricType(request.getMetricType() != null ? request.getMetricType() : "cosine")
                .indexType(request.getIndexType() != null ? request.getIndexType() : "hnsw")
                .M(16)
                .efConstruction(200)
                .build();

        List<VectorDocument> documents = IntStream.range(0, request.getVectors().size())
                .mapToObj(i -> VectorDocument.builder()
                        .id(request.getVectorIds().get(i))
                        .vector(request.getVectors().get(i))
                        .build())
                .collect(Collectors.toList());

        return buildUseCase.build(config, documents)
                .map(Result::success);
    }

    @PostMapping("/search")
    public Mono<Result<List<Neighbor>>> search(@RequestBody SearchWebRequest request) {
        int topK = request.getTopK() != null ? request.getTopK() : 10;
        return searchUseCase.search(request.getIndexId(), request.getQueryVector(), topK)
                .map(Result::success);
    }

    @PostMapping("/batch/build")
    public Mono<Result<Map<Long, IndexStats>>> batchBuild(@RequestBody List<BuildWebRequest> requests) {
        List<VectorIndexBatchBuildUseCase.BatchIndexBuildRequest> buildRequests = requests.stream()
                .map(r -> {
                    IndexConfig config = IndexConfig.builder()
                            .name(r.getIndexName())
                            .dimension(r.getDimension())
                            .metricType(r.getMetricType() != null ? r.getMetricType() : "cosine")
                            .indexType(r.getIndexType() != null ? r.getIndexType() : "hnsw")
                            .M(16)
                            .efConstruction(200)
                            .build();
                    List<VectorDocument> docs = IntStream.range(0, r.getVectors().size())
                            .mapToObj(i -> VectorDocument.builder()
                                    .id(r.getVectorIds().get(i))
                                    .vector(r.getVectors().get(i))
                                    .build())
                            .collect(Collectors.toList());
                    return VectorIndexBatchBuildUseCase.BatchIndexBuildRequest.builder()
                            .config(config)
                            .documents(docs)
                            .build();
                })
                .collect(Collectors.toList());

        return batchBuildUseCase.batchBuild(buildRequests)
                .map(Result::success);
    }

    @PostMapping("/batch/search")
    public Mono<Result<Map<Long, List<Neighbor>>>> batchSearch(@RequestBody List<SearchWebRequest> requests) {
        List<VectorBatchSearchUseCase.BatchSearchRequest> searchRequests = requests.stream()
                .map(r -> VectorBatchSearchUseCase.BatchSearchRequest.builder()
                        .indexId(r.getIndexId())
                        .query(r.getQueryVector())
                        .topK(r.getTopK() != null ? r.getTopK() : 10)
                        .build())
                .collect(Collectors.toList());

        return batchSearchUseCase.batchSearch(searchRequests)
                .map(Result::success);
    }

    @PostMapping("/{indexId}/documents/add")
    public Mono<Result<IndexStats>> addDocuments(
            @PathVariable Long indexId,
            @RequestBody AddDocumentsRequest request) {
        List<VectorDocument> documents = IntStream.range(0, request.getVectors().size())
                .mapToObj(i -> VectorDocument.builder()
                        .id(request.getVectorIds().get(i))
                        .vector(request.getVectors().get(i))
                        .build())
                .collect(Collectors.toList());

        return batchBuildUseCase.addDocuments(indexId, documents)
                .map(Result::success);
    }

    @DeleteMapping("/{indexId}/documents")
    public Mono<Result<Void>> removeDocuments(
            @PathVariable Long indexId,
            @RequestBody RemoveDocumentsRequest request) {
        return batchBuildUseCase.removeDocuments(indexId, request.getDocumentIds())
                .map(Result::success);
    }

    @Data
    public static class BuildWebRequest {
        private String indexName;
        private Integer dimension;
        private String metricType;
        private String indexType;
        private List<float[]> vectors;
        private List<String> vectorIds;
    }

    @Data
    public static class SearchWebRequest {
        private Long indexId;
        private float[] queryVector;
        private Integer topK;
    }

    @Data
    public static class AddDocumentsRequest {
        private List<float[]> vectors;
        private List<String> vectorIds;
    }

    @Data
    public static class RemoveDocumentsRequest {
        private List<Long> documentIds;
    }
}
