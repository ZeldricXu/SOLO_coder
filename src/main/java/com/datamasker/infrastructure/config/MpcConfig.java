package com.datamasker.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "datamasker.mpc")
public class MpcConfig {

    private long protocolTimeout = 30000;

    private int maxParties = 10;
}
