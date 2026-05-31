package com.edgescheduler.aggregation.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class DataStreamDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String streamId;
    private String streamName;

    @NotEmpty(message = "deviceKey cannot be empty")
    private String deviceKey;

    @NotEmpty(message = "dataType cannot be empty")
    private String dataType;

    private String aggregationType;
    private String aggregationWindow;
    private List<Map<String, Object>> fieldsConfig;
    private Integer enabled;
    private LocalDateTime lastAggregatedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
