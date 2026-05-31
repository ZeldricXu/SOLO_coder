package com.solo.config.module.flow;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "flow")
public class FlowProperties {

    private Canary canary = new Canary();
    private BlueGreen blueGreen = new BlueGreen();
    private Mirroring mirroring = new Mirroring();
    private CircuitBreaker circuitBreaker = new CircuitBreaker();

    @Data
    public static class Canary {
        private boolean enabled = true;
        private int defaultWeight = 10;
    }

    @Data
    public static class BlueGreen {
        private boolean enabled = true;
        private String activeColor = "blue";
    }

    @Data
    public static class Mirroring {
        private boolean enabled = false;
        private String targetUrl;
    }

    @Data
    public static class CircuitBreaker {
        private int failureRateThreshold = 50;
        private long waitDurationInOpenState = 60000;
        private int permittedNumberOfCallsInHalfOpenState = 10;
        private int slidingWindowSize = 100;
    }
}
