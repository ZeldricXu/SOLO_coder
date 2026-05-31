package com.solocoder.dns.traffic.model;

import lombok.Data;
import java.io.Serializable;

@Data
public class BlueGreenConfig implements Serializable {
    private String blueVersion;
    private String greenVersion;
    private String activeVersion;
    private Integer trafficPercentToGreen;
    private Boolean autoRollbackEnabled;
    private Integer healthCheckThreshold;
}
