package com.tsdbproxy.metadata.crawler.app;

import com.tsdbproxy.metadata.crawler.api.MetadataCrawlUseCase;
import com.tsdbproxy.metadata.crawler.model.CrawlResult;
import com.tsdbproxy.metadata.crawler.model.CrawlTask;
import com.tsdbproxy.metadata.crawler.spi.CrawlResultCache;
import com.tsdbproxy.metadata.crawler.spi.DatasourceAccessor;
import com.tsdbproxy.metadata.crawler.spi.ResultPersister;
import com.tsdbproxy.metadata.crawler.spi.SchemaExtractor;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataCrawlService implements MetadataCrawlUseCase {

    private final DatasourceAccessor datasourceAccessor;
    private final SchemaExtractor schemaExtractor;
    private final ResultPersister resultPersister;
    private final CrawlResultCache crawlResultCache;

    private final Counter crawlTotalCounter;
    private final Counter crawlSuccessCounter;
    private final Counter crawlFailCounter;
    private final Counter cacheHitCounter;
    private final Counter cacheMissCounter;
    private final Timer crawlLatencyTimer;

    public MetadataCrawlService(
            DatasourceAccessor datasourceAccessor,
            SchemaExtractor schemaExtractor,
            ResultPersister resultPersister,
            CrawlResultCache crawlResultCache,
            MeterRegistry meterRegistry) {
        this.datasourceAccessor = datasourceAccessor;
        this.schemaExtractor = schemaExtractor;
        this.resultPersister = resultPersister;
        this.crawlResultCache = crawlResultCache;

        this.crawlTotalCounter = Counter.builder("metadata.crawl.total")
                .description("总采集次数")
                .register(meterRegistry);
        this.crawlSuccessCounter = Counter.builder("metadata.crawl.success")
                .description("成功采集次数")
                .register(meterRegistry);
        this.crawlFailCounter = Counter.builder("metadata.crawl.fail")
                .description("失败采集次数")
                .register(meterRegistry);
        this.cacheHitCounter = Counter.builder("metadata.crawl.cache.hit")
                .description("缓存命中次数")
                .register(meterRegistry);
        this.cacheMissCounter = Counter.builder("metadata.crawl.cache.miss")
                .description("缓存未命中次数")
                .register(meterRegistry);
        this.crawlLatencyTimer = Timer.builder("metadata.crawl.latency")
                .description("采集延迟")
                .register(meterRegistry);
    }

    @Override
    public Mono<CrawlResult> execute(CrawlTask task) {
        crawlTotalCounter.increment();
        Timer.Sample sample = Timer.start();

        log.info("开始元数据采集: datasourceId={}, table={}", task.getDatasourceId(), task.getTableName());

        return crawlResultCache.get(task)
                .doOnNext(result -> {
                    cacheHitCounter.increment();
                    log.info("缓存命中: datasourceId={}, table={}", task.getDatasourceId(), task.getTableName());
                })
                .switchIfEmpty(Mono.defer(() -> {
                    cacheMissCounter.increment();
                    return doCrawl(task)
                            .flatMap(result -> crawlResultCache.put(task, result)
                                    .then(Mono.just(result)));
                }))
                .doOnSuccess(result -> {
                    if ("success".equals(result.getStatus())) {
                        crawlSuccessCounter.increment();
                    } else {
                        crawlFailCounter.increment();
                    }
                    sample.stop(crawlLatencyTimer);
                    log.info("元数据采集完成: status={}", result.getStatus());
                })
                .doOnError(e -> {
                    crawlFailCounter.increment();
                    sample.stop(crawlLatencyTimer);
                    log.error("元数据采集失败", e);
                });
    }

    private Mono<CrawlResult> doCrawl(CrawlTask task) {
        return datasourceAccessor.getConnection(task)
                .flatMap(conn -> Mono.fromCallable(() -> {
                    try (conn) {
                        return schemaExtractor.extract(conn, task);
                    }
                }))
                .doOnNext(result -> resultPersister.persist(task, result));
    }
}
