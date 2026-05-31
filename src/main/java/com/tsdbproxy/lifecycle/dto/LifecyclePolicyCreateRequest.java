package com.tsdbproxy.lifecycle.dto;

import lombok.Data;

@Data
public class LifecyclePolicyCreateRequest {

    private String name;
    private String tableName;
    private String timeColumn;
    private Integer hotDays;
    private Integer coldDays;
    private Integer archiveDays;
    private String archiveLocation;
    private Integer enabled = 1;
}
