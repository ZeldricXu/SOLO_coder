package com.web3platform.chainindexer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "indexer")
public class IndexerProperties {

    private int batchSize = 100;
    private int concurrency = 4;
    private long retryInterval = 5000;
    private int maxRetries = 3;
    private long realtimePollInterval = 3000;
}
