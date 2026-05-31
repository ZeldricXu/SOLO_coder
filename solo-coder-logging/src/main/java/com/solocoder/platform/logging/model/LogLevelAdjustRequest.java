package com.solocoder.platform.logging.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogLevelAdjustRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "Logger name must not be blank")
    private String loggerName;

    @NotBlank(message = "Log level must not be blank")
    private String level;

    private String scope;

    @Builder.Default
    private long ttlSeconds = 3600L;
}
