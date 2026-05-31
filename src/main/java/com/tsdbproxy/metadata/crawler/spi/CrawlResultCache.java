package com.tsdbproxy.metadata.crawler.spi;

import com.tsdbproxy.metadata.crawler.model.CrawlResult;
import com.tsdbproxy.metadata.crawler.model.CrawlTask;
import reactor.core.publisher.Mono;

import java.time.Duration;

public interface CrawlResultCache {

    Mono<CrawlResult> get(CrawlTask task);

    Mono<Void> put(CrawlTask task, CrawlResult result);

    Mono<Void> invalidate(CrawlTask task);

    Mono<Void> invalidateAll();

    Mono<Void> warmUp(Iterable<CrawlTask> tasks);

    Duration getDefaultTtl();
}
