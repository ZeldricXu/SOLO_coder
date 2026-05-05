package com.orderflow.statistics;

import lombok.Data;

import java.io.Serializable;

@Data
public class StatisticsTaskResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private String taskId;
    private String status;
    private Object result;
    private String errorMessage;
    private long completedAt;
}
