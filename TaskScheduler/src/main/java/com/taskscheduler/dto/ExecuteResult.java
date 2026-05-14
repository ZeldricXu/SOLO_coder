package com.taskscheduler.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExecuteResult {

    private String executeId;
    private String taskId;
    private boolean success;
    private String result;
    private String errorMessage;
    private long durationSeconds;
}
