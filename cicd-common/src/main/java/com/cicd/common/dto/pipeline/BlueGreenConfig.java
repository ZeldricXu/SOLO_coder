package com.cicd.common.dto.pipeline;

import lombok.Data;

@Data
public class BlueGreenConfig {
    private String blueLabel;
    private String greenLabel;
    private boolean autoSwitch;
    private int switchDelay;
}
