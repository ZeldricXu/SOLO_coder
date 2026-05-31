package com.solocoder.dns.traffic.model;

import lombok.Data;
import java.io.Serializable;

@Data
public class TrafficMirrorConfig implements Serializable {
    private String sourceService;
    private String targetService;
    private Integer trafficPercent;
    private Boolean includeHeaders;
    private Boolean includeBody;
    private String filterExpression;
}
