package com.supplychain.common.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoryRecord implements Serializable {
    private String recordId;
    private String recordType;
    private String relatedId;
    private String action;
    private String operator;
    private String detail;
    private LocalDateTime createdAt;
}
