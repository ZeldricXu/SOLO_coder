package com.orderflow.statistics;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class StatisticsTaskResponse {
    private String taskId;
    private String status;
    private String message;
}
