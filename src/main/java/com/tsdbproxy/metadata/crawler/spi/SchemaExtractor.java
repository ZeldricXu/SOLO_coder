package com.tsdbproxy.metadata.crawler.spi;

import com.tsdbproxy.metadata.crawler.model.CrawlResult;
import com.tsdbproxy.metadata.crawler.model.CrawlTask;

import java.sql.Connection;

public interface SchemaExtractor {

    CrawlResult extract(Connection conn, CrawlTask task);
}
