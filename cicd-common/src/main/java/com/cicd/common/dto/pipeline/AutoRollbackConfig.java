package com.cicd.common.dto.pipeline;

import lombok.Data;

@Data
public class AutoRollbackConfig {
    private boolean enabled;
    private int maxErrorRate;
    private int observationWindow;
}
