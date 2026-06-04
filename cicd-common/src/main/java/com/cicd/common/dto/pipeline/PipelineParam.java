package com.cicd.common.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class PipelineParam {

    @NotBlank(message = "Param name is required")
    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("type")
    @Pattern(regexp = "string|number|boolean|choice", message = "Param type must be string, number, boolean, or choice")
    private String type;

    @JsonProperty("default_value")
    private String defaultValue;

    @JsonProperty("required")
    private boolean required;

    @JsonProperty("options")
    private String[] options;

    @JsonProperty("from_git_event")
    private String fromGitEvent;
}
