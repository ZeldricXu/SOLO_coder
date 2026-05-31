package com.metricplatform.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class LogLevelDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "Logger名称不能为空")
    private String loggerName;

    @NotBlank(message = "日志级别不能为空")
    @Pattern(regexp = "^(TRACE|DEBUG|INFO|WARN|ERROR|FATAL|OFF)$", message = "无效的日志级别，必须是TRACE/DEBUG/INFO/WARN/ERROR/FATAL/OFF")
    private String level;

    private LocalDateTime expireAt;

    private String createdBy;
}
