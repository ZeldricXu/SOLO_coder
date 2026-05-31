package com.datastandard.modules.logging.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogLevelChangeRequest {

    @NotBlank(message = "包路径不能为空")
    private String packagePath;

    @NotBlank(message = "日志级别不能为空")
    private String level;

    private boolean persistent;

    private Long durationMinutes;
}
