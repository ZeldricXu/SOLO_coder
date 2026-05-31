package com.tsdbproxy.metadata.crawler.spi;

import com.tsdbproxy.metadata.crawler.model.CrawlResult;
import com.tsdbproxy.metadata.crawler.model.CrawlTask;

public interface ResultPersister {

    void persist(CrawlTask task, CrawlResult result);
}
