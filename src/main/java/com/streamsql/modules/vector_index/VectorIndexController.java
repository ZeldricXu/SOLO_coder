package com.streamsql.modules.vector_index;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.streamsql.common.ApiResponse;
import com.streamsql.common.PageResult;
import com.streamsql.dto.VectorIndexDTO;
import com.streamsql.dto.VectorSearchDTO;
import com.streamsql.entity.VectorIndex;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/vector")
@RequiredArgsConstructor
public class VectorIndexController {

    private final VectorIndexService vectorIndexService;

    @PostMapping("/indexes")
    public Mono<ApiResponse<VectorIndex>> createIndex(@Validated @RequestBody VectorIndexDTO dto) throws JsonProcessingException {
        return Mono.just(ApiResponse.created(vectorIndexService.createIndex(dto)));
    }

    @DeleteMapping("/indexes/{indexId}")
    public Mono<ApiResponse<Void>> deleteIndex(@PathVariable String indexId) {
        vectorIndexService.deleteIndex(indexId);
        return Mono.just(ApiResponse.success(null));
    }

    @GetMapping("/indexes/{indexId}")
    public Mono<ApiResponse<VectorIndex>> getIndex(@PathVariable String indexId) {
        return Mono.just(ApiResponse.success(vectorIndexService.getIndex(indexId)));
    }

    @GetMapping("/indexes")
    public Mono<ApiResponse<PageResult<VectorIndex>>> listIndexes(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String datasourceId,
            @RequestParam(required = false) String status) {
        return Mono.just(ApiResponse.success(vectorIndexService.listIndexes(page, size, datasourceId, status)));
    }

    @PostMapping("/indexes/{indexId}/build")
    public Mono<ApiResponse<Void>> buildIndex(@PathVariable String indexId) {
        vectorIndexService.buildIndexAsync(indexId);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/indexes/{indexId}/search")
    public Mono<ApiResponse<List<Map<String, Object>>>> search(
            @PathVariable String indexId,
            @Validated @RequestBody VectorSearchDTO dto) {
        return Mono.just(ApiResponse.success(vectorIndexService.search(indexId, dto)));
    }

    @PostMapping("/indexes/{indexId}/embeddings")
    public Mono<ApiResponse<Void>> addEmbedding(
            @PathVariable String indexId,
            @RequestBody Map<String, Object> request) throws JsonProcessingException {
        String dataKey = (String) request.get("dataKey");
        @SuppressWarnings("unchecked")
        List<Number> vectorList = (List<Number>) request.get("vector");
        float[] vector = new float[vectorList.size()];
        for (int i = 0; i < vectorList.size(); i++) {
            vector[i] = vectorList.get(i).floatValue();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) request.get("metadata");

        vectorIndexService.addEmbedding(indexId, dataKey, vector, metadata);
        return Mono.just(ApiResponse.success(null));
    }
}
