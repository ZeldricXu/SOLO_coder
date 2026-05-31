package com.solocoder.dns.common.entity;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class StatsSnapshot implements Serializable {
    private String snapshotId;
    private LocalDateTime timestamp;
    private Map<String, Object> metrics;
    private Map<String, String> dimensions;
}
