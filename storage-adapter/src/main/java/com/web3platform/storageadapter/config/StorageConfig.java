package com.web3platform.storageadapter.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "storage")
public class StorageConfig {

    private String ipfsApiUrl = "http://127.0.0.1:5001";
    private String arweaveGatewayUrl = "https://arweave.net";
    private int chunkSize = 4 * 1024 * 1024;
    private int maxConcurrentUploads = 5;
    private int streamBufferSize = 8192;
}
