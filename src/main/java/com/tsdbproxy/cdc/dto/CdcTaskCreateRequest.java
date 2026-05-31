package com.tsdbproxy.cdc.dto;

import lombok.Data;

import java.util.Map;

@Data
public class CdcTaskCreateRequest {

    private String name;
    private Long datasourceId;
    private String tableName;
    private String outputType;
    private Map<String, Object> outputConfig;
}
