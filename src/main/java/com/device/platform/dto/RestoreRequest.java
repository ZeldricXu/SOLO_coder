package com.device.platform.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RestoreRequest {
    @NotBlank(message = "backupId不能为空")
    private String backupId;

    private String restoreScope;
}
