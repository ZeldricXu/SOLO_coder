package com.cicd.common.dto.pipeline;

import lombok.Data;
import java.util.List;

@Data
public class SmokeTestConfig {
    private boolean enabled;
    private List<SmokeTestEndpoint> endpoints;
    private int timeout;
    private int retries;
}
