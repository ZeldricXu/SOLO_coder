package com.cicd.common.dto.pipeline;

import lombok.Data;
import java.util.List;

@Data
public class CanaryConfig {
    private List<Integer> trafficSteps;
    private int stepInterval;
    private List<String> metrics;
    private double maxErrorRate;
}
