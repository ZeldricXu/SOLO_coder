package com.taskscheduler.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TriggerTaskRequest {

    private String taskId;
    private String triggerType = "manual";
}
