package com.metricplatform.controller;

import com.metricplatform.common.ApiResponse;
import com.metricplatform.entity.SysVectorEmbedding;
import com.metricplatform.entity.SysVectorIndex;
import com.metricplatform.service.VectorIndexService;
import com.metricplatform.util.SimpleVectorUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/vector")
@RequiredArgsConstructor
public class VectorController {

    private final VectorIndexService vectorIndexService;

    @GetMapping("/indexes")
    public Mono<ApiResponse<List<SysVectorIndex>>> getAllIndexes() {
        return Mono.just(ApiResponse.success(vectorIndexService.getAllIndexes()));
    }

    @GetMapping("/indexes/{indexId}")
    public Mono<ApiResponse<SysVectorIndex>> getIndex(@PathVariable String indexId) {
        SysVectorIndex index = vectorIndexService.getIndexById(indexId);
        if (index != null) {
            return Mono.just(ApiResponse.success(index));
        } else {
            return Mono.just(ApiResponse.notFound("索引不存在"));
        }
    }

    @PostMapping("/indexes")
    public Mono<ApiResponse<SysVectorIndex>> createIndex(@RequestBody Map<String, Object> request) {
        String indexName = (String) request.get("indexName");
        String description = (String) request.getOrDefault("description", "");
        Integer dimension = (Integer) request.get("dimension");
        String similarity = (String) request.get("similarity");
        String indexType = (String) request.get("indexType");
        @SuppressWarnings("unchecked")
        Map<String, Object> buildConfig = (Map<String, Object>) request.get("buildConfig");

        if (dimension == null || dimension <= 0) {
            return Mono.just(ApiResponse.badRequest("必须指定有效的向量维度"));
        }

        SysVectorIndex index = vectorIndexService.createIndex(
                indexName, description, dimension, similarity, indexType, buildConfig);
        return Mono.just(ApiResponse.created(index));
    }

    @DeleteMapping("/indexes/{indexId}")
    public Mono<ApiResponse<Void>> deleteIndex(@PathVariable String indexId) {
        boolean result = vectorIndexService.deleteIndex(indexId);
        if (result) {
            return Mono.just(ApiResponse.success(null));
        } else {
            return Mono.just(ApiResponse.notFound("索引不存在"));
        }
    }

    @PostMapping("/indexes/{indexId}/embeddings")
    public Mono<ApiResponse<SysVectorEmbedding>> addEmbedding(
            @PathVariable String indexId,
            @RequestBody Map<String, Object> request) {
        try {
            String originalId = (String) request.get("originalId");
            String text = (String) request.get("text");
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) request.get("metadata");

            SysVectorEmbedding embedding = vectorIndexService.addEmbedding(
                    indexId, originalId, text, metadata);
            return Mono.just(ApiResponse.created(embedding));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Mono.just(ApiResponse.badRequest(e.getMessage()));
        }
    }

    @PostMapping("/indexes/{indexId}/embeddings/batch")
    public Mono<ApiResponse<Map<String, Object>>> batchAddEmbeddings(
            @PathVariable String indexId,
            @RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) request.get("items");
            vectorIndexService.batchAddEmbeddings(indexId, items);

            Map<String, Object> result = new HashMap<>();
            result.put("indexId", indexId);
            result.put("count", items.size());
            result.put("message", "批量添加完成");
            return Mono.just(ApiResponse.success(result));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.badRequest(e.getMessage()));
        }
    }

    @GetMapping("/indexes/{indexId}/embeddings")
    public Mono<ApiResponse<List<SysVectorEmbedding>>> getEmbeddings(
            @PathVariable String indexId,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "100") int limit) {
        List<SysVectorEmbedding> embeddings = vectorIndexService.getEmbeddings(indexId, offset, limit);
        return Mono.just(ApiResponse.success(embeddings));
    }

    @DeleteMapping("/embeddings/{embeddingId}")
    public Mono<ApiResponse<Void>> deleteEmbedding(@PathVariable String embeddingId) {
        boolean result = vectorIndexService.deleteEmbedding(embeddingId);
        if (result) {
            return Mono.just(ApiResponse.success(null));
        } else {
            return Mono.just(ApiResponse.notFound("向量不存在"));
        }
    }

    @PostMapping("/indexes/{indexId}/search")
    public Mono<ApiResponse<List<VectorIndexService.SearchResult>>> search(
            @PathVariable String indexId,
            @RequestBody Map<String, Object> request) {
        try {
            String query = (String) request.get("query");
            Integer topK = (Integer) request.getOrDefault("topK", 10);
            String metric = (String) request.get("metric");
            String searchType = (String) request.getOrDefault("searchType", "brute");

            List<VectorIndexService.SearchResult> results;

            if ("text".equals(request.get("queryType")) || request.get("vector") == null) {
                results = "kdtree".equals(searchType) ?
                        vectorIndexService.searchByKDTree(indexId,
                                SimpleVectorUtil.generateVectorFromText(query,
                                        vectorIndexService.getIndexById(indexId).getDimension()),
                                topK, metric) :
                        vectorIndexService.search(indexId, query, topK, metric);
            } else {
                @SuppressWarnings("unchecked")
                List<Number> vectorList = (List<Number>) request.get("vector");
                float[] vector = new float[vectorList.size()];
                for (int i = 0; i < vectorList.size(); i++) {
                    vector[i] = vectorList.get(i).floatValue();
                }
                results = "kdtree".equals(searchType) ?
                        vectorIndexService.searchByKDTree(indexId, vector, topK, metric) :
                        vectorIndexService.searchByVector(indexId, vector, topK, metric);
            }

            return Mono.just(ApiResponse.success(results));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Mono.just(ApiResponse.badRequest(e.getMessage()));
        }
    }

    @GetMapping("/indexes/{indexId}/stats")
    public Mono<ApiResponse<Map<String, Object>>> getIndexStats(@PathVariable String indexId) {
        SysVectorIndex index = vectorIndexService.getIndexById(indexId);
        if (index == null) {
            return Mono.just(ApiResponse.notFound("索引不存在"));
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("indexId", index.getIndexId());
        stats.put("indexName", index.getIndexName());
        stats.put("dimension", index.getDimension());
        stats.put("similarity", index.getSimilarity());
        stats.put("status", index.getStatus());
        stats.put("embeddingCount", vectorIndexService.getEmbeddingCount(indexId));
        stats.put("builtAt", index.getBuiltAt());
        stats.put("lastUpdatedAt", index.getLastUpdatedAt());

        return Mono.just(ApiResponse.success(stats));
    }
}
