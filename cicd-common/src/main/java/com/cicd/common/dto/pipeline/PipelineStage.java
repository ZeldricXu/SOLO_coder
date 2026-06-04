package com.cicd.common.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import java.util.List;

@Data
public class PipelineStage {

    @NotBlank(message = "Stage name is required")
    @JsonProperty("name")
    private String name;

    @JsonProperty("condition")
    private String condition;

    @JsonProperty("parallel")
    private boolean parallel;

    @NotEmpty(message = "Stage must have at least one job")
    @JsonProperty("jobs")
    private List<@Valid PipelineJob> jobs;
}
