package com.web3platform.crosschainbridge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "cross-chain-bridge.pool")
public class ResourcePoolConfig {

    private int rpcPoolMaxTotal = 10;
    private int rpcPoolMaxPerRoute = 5;
    private int rpcPoolIdleSeconds = 300;

    private int verifierPoolSize = 5;
    private long verifierPoolMaxWaitMs = 5000;

    private int messageSignerPoolSize = 3;

    private int connectionTimeoutMs = 10000;
    private int readTimeoutMs = 30000;

    private Map<String, String> chainRpc;
}
