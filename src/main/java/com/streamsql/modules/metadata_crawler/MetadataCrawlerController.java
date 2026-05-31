package com.streamsql.modules.metadata_crawler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.streamsql.common.ApiResponse;
import com.streamsql.common.PageResult;
import com.streamsql.dto.DatasourceDTO;
import com.streamsql.entity.DatasourceInfo;
import com.streamsql.entity.MetadataSchema;
import com.streamsql.entity.MetadataStatistics;
import com.streamsql.entity.SampleData;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/metadata")
@RequiredArgsConstructor
public class MetadataCrawlerController {

    private final MetadataCrawlerService metadataCrawlerService;

    @PostMapping("/datasources")
    public Mono<ApiResponse<DatasourceInfo>> createDatasource(@Validated @RequestBody DatasourceDTO dto) throws JsonProcessingException {
        return Mono.just(ApiResponse.created(metadataCrawlerService.createDatasource(dto)));
    }

    @PutMapping("/datasources/{datasourceId}")
    public Mono<ApiResponse<DatasourceInfo>> updateDatasource(
            @PathVariable String datasourceId,
            @Validated @RequestBody DatasourceDTO dto) throws JsonProcessingException {
        return Mono.just(ApiResponse.success(metadataCrawlerService.updateDatasource(datasourceId, dto)));
    }

    @DeleteMapping("/datasources/{datasourceId}")
    public Mono<ApiResponse<Void>> deleteDatasource(@PathVariable String datasourceId) {
        metadataCrawlerService.deleteDatasource(datasourceId);
        return Mono.just(ApiResponse.success(null));
    }

    @GetMapping("/datasources/{datasourceId}")
    public Mono<ApiResponse<DatasourceInfo>> getDatasource(@PathVariable String datasourceId) {
        return Mono.just(ApiResponse.success(metadataCrawlerService.getDatasource(datasourceId)));
    }

    @GetMapping("/datasources")
    public Mono<ApiResponse<PageResult<DatasourceInfo>>> listDatasources(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String datasourceType,
            @RequestParam(required = false) String status) {
        return Mono.just(ApiResponse.success(metadataCrawlerService.listDatasources(page, size, datasourceType, status)));
    }

    @PostMapping("/datasources/test")
    public Mono<ApiResponse<Boolean>> testConnection(@Validated @RequestBody DatasourceDTO dto) {
        return Mono.just(ApiResponse.success(metadataCrawlerService.testConnection(dto)));
    }

    @PostMapping("/datasources/{datasourceId}/crawl")
    public Mono<ApiResponse<Void>> crawlMetadata(@PathVariable String datasourceId) {
        metadataCrawlerService.crawlMetadataAsync(datasourceId);
        return Mono.just(ApiResponse.success(null));
    }

    @GetMapping("/datasources/{datasourceId}/schema")
    public Mono<ApiResponse<List<MetadataSchema>>> getTableSchema(
            @PathVariable String datasourceId,
            @RequestParam(required = false) String schemaName,
            @RequestParam(required = false) String tableName) {
        return Mono.just(ApiResponse.success(metadataCrawlerService.getTableSchema(datasourceId, schemaName, tableName)));
    }

    @GetMapping("/datasources/{datasourceId}/statistics")
    public Mono<ApiResponse<List<MetadataStatistics>>> getTableStatistics(
            @PathVariable String datasourceId,
            @RequestParam(required = false) String schemaName,
            @RequestParam(required = false) String tableName) {
        return Mono.just(ApiResponse.success(metadataCrawlerService.getTableStatistics(datasourceId, schemaName, tableName)));
    }

    @GetMapping("/datasources/{datasourceId}/sample")
    public Mono<ApiResponse<List<SampleData>>> getSampleData(
            @PathVariable String datasourceId,
            @RequestParam(required = false) String schemaName,
            @RequestParam(required = false) String tableName,
            @RequestParam(defaultValue = "10") int limit) {
        return Mono.just(ApiResponse.success(metadataCrawlerService.getSampleData(datasourceId, schemaName, tableName, limit)));
    }
}
