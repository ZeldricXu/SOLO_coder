package com.logistics.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class AssignTaskRequest {

    @NotBlank(message = "logistics_id不能为空")
    private String logisticsId;

    @NotBlank(message = "courier_id不能为空")
    private String courierId;

    private String urgencyLevel;
}
