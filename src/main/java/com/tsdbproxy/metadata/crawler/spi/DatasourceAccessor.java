package com.tsdbproxy.metadata.crawler.spi;

import com.tsdbproxy.metadata.crawler.model.CrawlTask;
import reactor.core.publisher.Mono;

import java.sql.Connection;

public interface DatasourceAccessor {

    Mono<Connection> getConnection(CrawlTask task);

    String getType();
}
