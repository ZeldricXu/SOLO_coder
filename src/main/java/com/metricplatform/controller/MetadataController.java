package com.metricplatform.controller;

import com.metricplatform.common.ApiResponse;
import com.metricplatform.entity.SysMetadataSchema;
import com.metricplatform.entity.SysMetadataSource;
import com.metricplatform.service.MetadataCrawlerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/metadata")
@RequiredArgsConstructor
public class MetadataController {

    private final MetadataCrawlerService metadataCrawlerService;

    @GetMapping("/sources")
    public Mono<ApiResponse<List<SysMetadataSource>>> getAllSources() {
        return Mono.just(ApiResponse.success(metadataCrawlerService.getAllSources()));
    }

    @GetMapping("/sources/{sourceId}")
    public Mono<ApiResponse<SysMetadataSource>> getSource(@PathVariable String sourceId) {
        SysMetadataSource source = metadataCrawlerService.getById(sourceId);
        if (source != null) {
            return Mono.just(ApiResponse.success(source));
        } else {
            return Mono.just(ApiResponse.notFound("数据源不存在"));
        }
    }

    @PostMapping("/sources")
    public Mono<ApiResponse<SysMetadataSource>> createSource(@RequestBody Map<String, Object> request) {
        String sourceName = (String) request.get("sourceName");
        String sourceType = (String) request.get("sourceType");
        @SuppressWarnings("unchecked")
        Map<String, Object> connectionConfig = (Map<String, Object>) request.get("connectionConfig");
        Integer scanInterval = (Integer) request.get("scanInterval");

        SysMetadataSource source = metadataCrawlerService.createSource(
                sourceName, sourceType, connectionConfig,
                scanInterval != null ? scanInterval.longValue() : null);
        return Mono.just(ApiResponse.created(source));
    }

    @PostMapping("/sources/{sourceId}/scan")
    public Mono<ApiResponse<Map<String, Object>>> scanSource(@PathVariable String sourceId) {
        SysMetadataSource source = metadataCrawlerService.getById(sourceId);
        if (source == null) {
            return Mono.just(ApiResponse.notFound("数据源不存在"));
        }

        metadataCrawlerService.scanSourceAsync(source);

        Map<String, Object> result = new HashMap<>();
        result.put("sourceId", sourceId);
        result.put("sourceName", source.getSourceName());
        result.put("message", "数据源扫描已异步启动");
        return Mono.just(ApiResponse.success(result));
    }

    @PostMapping("/sources/{sourceId}/status")
    public Mono<ApiResponse<SysMetadataSource>> updateSourceStatus(
            @PathVariable String sourceId,
            @RequestBody Map<String, String> request) {
        try {
            String status = request.get("status");
            SysMetadataSource source = metadataCrawlerService.updateSourceStatus(sourceId, status);
            return Mono.just(ApiResponse.success(source));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.notFound(e.getMessage()));
        }
    }

    @DeleteMapping("/sources/{sourceId}")
    public Mono<ApiResponse<Void>> deleteSource(@PathVariable String sourceId) {
        boolean result = metadataCrawlerService.deleteSource(sourceId);
        if (result) {
            return Mono.just(ApiResponse.success(null));
        } else {
            return Mono.just(ApiResponse.notFound("数据源不存在"));
        }
    }

    @GetMapping("/schemas")
    public Mono<ApiResponse<List<SysMetadataSchema>>> getSchemas(
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String database,
            @RequestParam(required = false) String table) {
        List<SysMetadataSchema> schemas = metadataCrawlerService.getSchemas(sourceId, database, table);
        return Mono.just(ApiResponse.success(schemas));
    }

    @GetMapping("/schemas/{schemaId}")
    public Mono<ApiResponse<SysMetadataSchema>> getSchemaById(@PathVariable String schemaId) {
        SysMetadataSchema schema = metadataCrawlerService.getSchemaById(schemaId);
        if (schema != null) {
            return Mono.just(ApiResponse.success(schema));
        } else {
            return Mono.just(ApiResponse.notFound("Schema不存在"));
        }
    }
}
