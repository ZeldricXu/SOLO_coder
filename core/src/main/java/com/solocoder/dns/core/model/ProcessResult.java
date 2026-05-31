package com.solocoder.dns.core.model;

import lombok.Data;
import java.io.Serializable;
import java.util.Map;

@Data
public class ProcessResult implements Serializable {
    private String runId;
    private String status;
    private String message;
    private Map<String, Object> data;
    private Long elapsedMs;
}
