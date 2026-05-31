package com.datamasker.interfaces.dto.audit;

import lombok.Data;

@Data
public class RecordLogRequest {

    private String operation;

    private String operator;

    private String module;

    private String detail;
}
