package com.streamsql.modules.data_lineage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.streamsql.common.ApiResponse;
import com.streamsql.common.PageResult;
import com.streamsql.dto.LineageParseDTO;
import com.streamsql.entity.LineageEdge;
import com.streamsql.entity.LineageGraph;
import com.streamsql.entity.LineageNode;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/lineage")
@RequiredArgsConstructor
public class DataLineageController {

    private final DataLineageService dataLineageService;

    @PostMapping("/parse")
    public Mono<ApiResponse<LineageGraph>> parseLineage(@Validated @RequestBody LineageParseDTO dto) throws JsonProcessingException {
        return Mono.just(ApiResponse.success(dataLineageService.parseLineage(dto)));
    }

    @GetMapping("/graphs/{lineageId}")
    public Mono<ApiResponse<LineageGraph>> getLineageGraph(@PathVariable String lineageId) {
        return Mono.just(ApiResponse.success(dataLineageService.getLineageGraph(lineageId)));
    }

    @GetMapping("/graphs")
    public Mono<ApiResponse<PageResult<LineageGraph>>> listLineageGraphs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String sourceType) {
        return Mono.just(ApiResponse.success(dataLineageService.listLineageGraphs(page, size, sourceType)));
    }

    @DeleteMapping("/graphs/{lineageId}")
    public Mono<ApiResponse<Void>> deleteLineageGraph(@PathVariable String lineageId) {
        dataLineageService.deleteLineageGraph(lineageId);
        return Mono.just(ApiResponse.success(null));
    }

    @GetMapping("/graphs/{lineageId}/nodes")
    public Mono<ApiResponse<List<LineageNode>>> getLineageNodes(@PathVariable String lineageId) {
        return Mono.just(ApiResponse.success(dataLineageService.getLineageNodes(lineageId)));
    }

    @GetMapping("/graphs/{lineageId}/edges")
    public Mono<ApiResponse<List<LineageEdge>>> getLineageEdges(@PathVariable String lineageId) {
        return Mono.just(ApiResponse.success(dataLineageService.getLineageEdges(lineageId)));
    }
}
