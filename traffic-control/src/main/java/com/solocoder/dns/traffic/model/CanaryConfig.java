package com.solocoder.dns.traffic.model;

import lombok.Data;
import java.io.Serializable;

@Data
public class CanaryConfig implements Serializable {
    private String targetVersion;
    private Integer trafficPercent;
    private String headerKey;
    private String headerValue;
    private String cookieKey;
    private String cookieValue;
    private String userGroup;
}
