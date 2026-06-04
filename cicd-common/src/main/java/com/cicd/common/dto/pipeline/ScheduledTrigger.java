package com.cicd.common.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Map;

@Data
public class ScheduledTrigger {

    @NotBlank(message = "Cron expression is required for scheduled trigger")
    @JsonProperty("cron")
    private String cron;

    @JsonProperty("timezone")
    private String timezone;

    @JsonProperty("params")
    private Map<String, String> params;
}
