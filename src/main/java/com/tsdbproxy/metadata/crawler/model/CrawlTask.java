package com.tsdbproxy.metadata.crawler.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class CrawlTask {
    private Long datasourceId;
    private String schemaName;
    private String tableName;
    private Integer sampleSize;
}
