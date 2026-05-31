package com.web3platform.gasestimator.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "gas-estimator")
public class GasEstimatorConfig {

    private boolean autoCollectEnabled = false;
    private Long collectIntervalMs = 60000L;
    private Map<String, Double> speedMultipliers = new HashMap<>();
    private Map<String, BigInteger> eip1559PriorityFee = new HashMap<>();

    public GasEstimatorConfig() {
        speedMultipliers.put("SLOW", 0.9);
        speedMultipliers.put("NORMAL", 1.0);
        speedMultipliers.put("FAST", 1.2);
        speedMultipliers.put("URGENT", 1.5);

        eip1559PriorityFee.put("SLOW", new BigInteger("1000000000"));
        eip1559PriorityFee.put("NORMAL", new BigInteger("2000000000"));
        eip1559PriorityFee.put("FAST", new BigInteger("3000000000"));
        eip1559PriorityFee.put("URGENT", new BigInteger("5000000000"));
    }
}
