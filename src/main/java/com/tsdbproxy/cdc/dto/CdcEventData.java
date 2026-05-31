package com.tsdbproxy.cdc.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class CdcEventData {

    private String eventType;
    private String database;
    private String tableName;
    private Map<String, Object> beforeData;
    private Map<String, Object> afterData;
    private String binlogPosition;
    private LocalDateTime eventTime;
}
