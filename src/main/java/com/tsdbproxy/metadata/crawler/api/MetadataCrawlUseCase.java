package com.tsdbproxy.metadata.crawler.api;

import com.tsdbproxy.metadata.crawler.model.CrawlResult;
import com.tsdbproxy.metadata.crawler.model.CrawlTask;
import reactor.core.publisher.Mono;

public interface MetadataCrawlUseCase {

    Mono<CrawlResult> execute(CrawlTask task);
}
