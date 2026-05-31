package com.cdcsync.metadata.controller;

import com.cdcsync.common.api.PageResult;
import com.cdcsync.common.api.Result;
import com.cdcsync.metadata.domain.SchemaInfo;
import com.cdcsync.metadata.domain.TableInfo;
import com.cdcsync.metadata.service.MetadataCrawlerService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/metadata")
@RequiredArgsConstructor
public class MetadataController {

    private final MetadataCrawlerService metadataCrawlerService;

    @PostMapping("/data-sources/{dataSourceId}/crawl")
    public Result<SchemaInfo> crawlFullSchema(@PathVariable String dataSourceId) {
        return Result.success(metadataCrawlerService.crawlFullSchema(dataSourceId));
    }

    @PostMapping("/data-sources/{dataSourceId}/tables/{tableName}/crawl")
    public Result<TableInfo> crawlTable(
            @PathVariable String dataSourceId,
            @PathVariable String tableName) {
        return Result.success(metadataCrawlerService.crawlTable(dataSourceId, tableName));
    }

    @GetMapping("/data-sources/{dataSourceId}/tables/{tableName}/analyze")
    public Result<Map<String, Object>> analyzeTable(
            @PathVariable String dataSourceId,
            @PathVariable String tableName) {
        return Result.success(metadataCrawlerService.analyzeTable(dataSourceId, tableName));
    }

    @GetMapping("/data-sources/{dataSourceId}/tables")
    public Result<List<TableInfo>> listTables(@PathVariable String dataSourceId) {
        return Result.success(metadataCrawlerService.listTables(dataSourceId));
    }

    @GetMapping("/data-sources/{dataSourceId}/tables/{tableName}")
    public Result<TableInfo> getTableInfo(
            @PathVariable String dataSourceId,
            @PathVariable String tableName) {
        return Result.success(metadataCrawlerService.getTableInfo(dataSourceId, tableName));
    }

    @GetMapping("/data-sources/{dataSourceId}/tables/{tableName}/sample")
    public Result<List<Map<String, Object>>> getSampleData(
            @PathVariable String dataSourceId,
            @PathVariable String tableName,
            @RequestParam(defaultValue = "100") int limit) {
        return Result.success(metadataCrawlerService.getSampleData(dataSourceId, tableName, limit));
    }

    @GetMapping("/schemas/{id}")
    public Result<SchemaInfo> findSchemaById(@PathVariable String id) {
        return Result.success(metadataCrawlerService.findById(id));
    }

    @GetMapping("/schemas")
    public Result<List<SchemaInfo>> findAllSchemas() {
        return Result.success(metadataCrawlerService.findAll());
    }

    @GetMapping("/schemas/page")
    public Result<PageResult<SchemaInfo>> findSchemaPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(metadataCrawlerService.findPage(pageNum, pageSize));
    }

    @DeleteMapping("/schemas/{id}")
    public Result<Void> deleteSchema(@PathVariable String id) {
        metadataCrawlerService.delete(id);
        return Result.success();
    }
}
