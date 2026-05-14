package com.logistics.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UpdateTaskResponse {

    private String taskId;
    private String status;
    private String message;
}
