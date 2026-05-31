package com.iotplatform.datastream.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class AggregationQueryDTO {

    private String deviceId;

    private String streamId;

    private String metricName;

    private List<String> aggregationTypes;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer windowSizeMs = 5000;

    private Boolean onlyUnuploaded;
}
