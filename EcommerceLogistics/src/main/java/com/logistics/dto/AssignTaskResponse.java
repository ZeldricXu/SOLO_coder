package com.logistics.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AssignTaskResponse {

    private String taskId;
    private String status;
}
