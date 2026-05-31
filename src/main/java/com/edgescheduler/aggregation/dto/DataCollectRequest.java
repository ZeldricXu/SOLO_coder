package com.edgescheduler.aggregation.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class DataCollectRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotEmpty(message = "deviceKey cannot be empty")
    private String deviceKey;

    @NotEmpty(message = "streamId cannot be empty")
    private String streamId;

    @NotNull(message = "data cannot be null")
    private Map<String, Object> data;

    private LocalDateTime timestamp;
}
