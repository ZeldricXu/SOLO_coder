package com.solocoder.dns.core.model;

import lombok.Data;
import java.io.Serializable;
import java.util.Map;

@Data
public class ProcessRequest implements Serializable {
    private String traceId;
    private String namespace;
    private Map<String, Object> params;
    private Map<String, Object> payload;
    private String userId;
}
