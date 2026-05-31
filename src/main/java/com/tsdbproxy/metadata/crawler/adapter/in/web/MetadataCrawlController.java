package com.tsdbproxy.metadata.crawler.adapter.in.web;

import com.tsdbproxy.common.result.Result;
import com.tsdbproxy.metadata.crawler.api.MetadataCrawlUseCase;
import com.tsdbproxy.metadata.crawler.model.CrawlResult;
import com.tsdbproxy.metadata.crawler.model.CrawlTask;
import com.tsdbproxy.metadata.crawler.spi.CrawlResultCache;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/metadata")
@RequiredArgsConstructor
public class MetadataCrawlController {

    private final MetadataCrawlUseCase metadataCrawlUseCase;
    private final CrawlResultCache crawlResultCache;

    @PostMapping("/crawl")
    public Mono<Result<CrawlResult>> crawl(@RequestBody CrawlWebRequest request) {
        CrawlTask task = CrawlTask.builder()
                .datasourceId(request.getDatasourceId())
                .schemaName(request.getSchemaName())
                .tableName(request.getTableName())
                .sampleSize(request.getSampleSize() != null ? request.getSampleSize() : 100)
                .build();

        return metadataCrawlUseCase.execute(task)
                .map(Result::success);
    }

    @PostMapping("/cache/invalidate")
    public Mono<Result<Void>> invalidateCache(@RequestBody CacheInvalidateRequest request) {
        CrawlTask task = CrawlTask.builder()
                .datasourceId(request.getDatasourceId())
                .schemaName(request.getSchemaName())
                .tableName(request.getTableName())
                .build();

        return crawlResultCache.invalidate(task)
                .then(Mono.just(Result.success()));
    }

    @PostMapping("/cache/invalidate-all")
    public Mono<Result<Void>> invalidateAllCache() {
        return crawlResultCache.invalidateAll()
                .then(Mono.just(Result.success()));
    }

    @PostMapping("/cache/warmup")
    public Mono<Result<Void>> warmUpCache(@RequestBody List<CrawlWebRequest> requests) {
        List<CrawlTask> tasks = requests.stream()
                .map(r -> CrawlTask.builder()
                        .datasourceId(r.getDatasourceId())
                        .schemaName(r.getSchemaName())
                        .tableName(r.getTableName())
                        .sampleSize(r.getSampleSize() != null ? r.getSampleSize() : 100)
                        .build())
                .toList();

        return crawlResultCache.warmUp(tasks)
                .then(Mono.just(Result.success()));
    }

    @Data
    public static class CrawlWebRequest {
        private Long datasourceId;
        private String schemaName;
        private String tableName;
        private Integer sampleSize;
    }

    @Data
    public static class CacheInvalidateRequest {
        private Long datasourceId;
        private String schemaName;
        private String tableName;
    }
}
