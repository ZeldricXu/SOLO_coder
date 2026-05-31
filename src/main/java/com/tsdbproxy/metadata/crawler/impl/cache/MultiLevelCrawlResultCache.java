package com.tsdbproxy.metadata.crawler.impl.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tsdbproxy.metadata.crawler.model.CrawlResult;
import com.tsdbproxy.metadata.crawler.model.CrawlTask;
import com.tsdbproxy.metadata.crawler.spi.Cache;
import com.tsdbproxy.metadata.crawler.spi.CrawlResultCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Slf4j
public class MultiLevelCrawlResultCache implements CrawlResultCache {

    private final Cache<String, CrawlResult> l1Cache;
    private final Cache<String, CrawlResult> l2Cache;
    private final Duration defaultTtl;

    private final Counter l1HitCounter;
    private final Counter l1MissCounter;
    private final Counter l2HitCounter;
    private final Counter l2MissCounter;
    private final Timer getLatencyTimer;

    public MultiLevelCrawlResultCache(
            ReactiveRedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            int l1MaxSize,
            Duration defaultTtl) {

        this.defaultTtl = defaultTtl;
        this.l1Cache = new L1LocalCache<>("crawl_result", l1MaxSize, defaultTtl);
        this.l2Cache = new L2DistributedCache<>(
                redisTemplate, objectMapper, "crawl_result", CrawlResult.class, defaultTtl);

        this.l1HitCounter = Counter.builder("metadata.cache.l1.hit")
                .description("L1缓存命中次数")
                .register(meterRegistry);
        this.l1MissCounter = Counter.builder("metadata.cache.l1.miss")
                .description("L1缓存未命中次数")
                .register(meterRegistry);
        this.l2HitCounter = Counter.builder("metadata.cache.l2.hit")
                .description("L2缓存命中次数")
                .register(meterRegistry);
        this.l2MissCounter = Counter.builder("metadata.cache.l2.miss")
                .description("L2缓存未命中次数")
                .register(meterRegistry);
        this.getLatencyTimer = Timer.builder("metadata.cache.get.latency")
                .description("缓存获取延迟")
                .register(meterRegistry);
    }

    @Override
    public Mono<CrawlResult> get(CrawlTask task) {
        String key = buildKey(task);
        Timer.Sample sample = Timer.start();

        return l1Cache.get(key)
                .doOnNext(result -> {
                    l1HitCounter.increment();
                    sample.stop(getLatencyTimer);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    l1MissCounter.increment();
                    return l2Cache.get(key)
                            .doOnNext(result -> {
                                l2HitCounter.increment();
                                l1Cache.put(key, result, defaultTtl).subscribe();
                                sample.stop(getLatencyTimer);
                            })
                            .doOnError(e -> {
                                l2MissCounter.increment();
                                sample.stop(getLatencyTimer);
                            });
                }));
    }

    @Override
    public Mono<Void> put(CrawlTask task, CrawlResult result) {
        String key = buildKey(task);
        return Mono.zip(
                l1Cache.put(key, result, defaultTtl),
                l2Cache.put(key, result, defaultTtl)
        ).then();
    }

    @Override
    public Mono<Void> invalidate(CrawlTask task) {
        String key = buildKey(task);
        return Mono.zip(
                l1Cache.invalidate(key),
                l2Cache.invalidate(key)
        ).then();
    }

    @Override
    public Mono<Void> invalidateAll() {
        return Mono.zip(
                l1Cache.invalidateAll(),
                l2Cache.invalidateAll()
        ).then();
    }

    @Override
    public Mono<Void> warmUp(Iterable<CrawlTask> tasks) {
        log.info("开始缓存预热");
        return Flux.fromIterable(tasks)
                .flatMap(task -> get(task).onErrorResume(e -> Mono.empty()))
                .collectList()
                .doOnSuccess(results -> log.info("缓存预热完成, 预热条数={}", results.size()))
                .then();
    }

    @Override
    public Duration getDefaultTtl() {
        return defaultTtl;
    }

    private String buildKey(CrawlTask task) {
        return task.getDatasourceId() + ":" + task.getSchemaName() + ":" + task.getTableName();
    }
}
