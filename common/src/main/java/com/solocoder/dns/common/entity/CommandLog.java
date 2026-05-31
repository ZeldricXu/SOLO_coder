package com.solocoder.dns.common.entity;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class CommandLog implements Serializable {
    private String commandId;
    private String commandType;
    private String aggregateId;
    private Map<String, Object> payload;
    private String userId;
    private LocalDateTime issuedAt;
    private String status;
    private String result;
}
