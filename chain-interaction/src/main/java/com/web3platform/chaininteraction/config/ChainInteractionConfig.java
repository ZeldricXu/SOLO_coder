package com.web3platform.chaininteraction.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "chain-interaction")
public class ChainInteractionConfig {

    private List<ChainConfig> chains = new ArrayList<>();

    @Data
    public static class ChainConfig {
        private String chainId;
        private String rpcUrl;
        private String chainType;
    }
}
