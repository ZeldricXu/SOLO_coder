package com.cicd.common.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import java.util.List;

@Data
public class PipelineJob {

    @NotBlank(message = "Job name is required")
    @JsonProperty("name")
    private String name;

    @JsonProperty("tags")
    private String[] tags;

    @Min(value = 0, message = "Job timeout must be non-negative")
    @JsonProperty("timeout")
    private int timeout;

    @JsonProperty("condition")
    private String condition;

    @NotEmpty(message = "Job must have at least one step")
    @JsonProperty("steps")
    private List<@Valid PipelineStep> steps;
}
