package com.datamasker.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "datamasker.shamir")
public class ShamirConfig {

    private int defaultThreshold = 3;

    private int defaultShares = 5;

    private int primeBits = 256;
}
