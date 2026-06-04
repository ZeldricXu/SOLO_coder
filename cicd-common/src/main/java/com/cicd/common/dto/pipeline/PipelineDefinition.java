package com.cicd.common.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class PipelineDefinition {

    @NotBlank(message = "Pipeline name is required")
    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("params")
    private List<@Valid PipelineParam> params;

    @JsonProperty("variables")
    private Map<String, String> variables;

    @NotEmpty(message = "Pipeline must have at least one stage")
    @JsonProperty("stages")
    private List<@Valid PipelineStage> stages;

    @JsonProperty("trigger")
    @Valid
    private PipelineTrigger trigger;

    @JsonProperty("notification")
    @Valid
    private PipelineNotification notification;
}
