package com.logistics.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateTaskRequest {

    @NotBlank(message = "task_id不能为空")
    private String taskId;

    @NotBlank(message = "action不能为空")
    private String action;

    private String location;

    private String detail;
}
