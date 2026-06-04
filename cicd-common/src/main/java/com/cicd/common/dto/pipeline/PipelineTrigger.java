package com.cicd.common.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import lombok.Data;
import java.util.List;

@Data
public class PipelineTrigger {

    @JsonProperty("webhooks")
    private List<@Valid WebhookTrigger> webhooks;

    @JsonProperty("schedules")
    private List<@Valid ScheduledTrigger> schedules;
}
