package com.taskscheduler.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RegisterExecutorRequest {

    private String executorId;
    private String executorName;
    private String executorAddress;
    private Integer maxCapacity;
    private String taskType;
}
