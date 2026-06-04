package com.cicd.common.dto.pipeline;

import com.cicd.common.enums.StepType;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.Map;

@Data
public class PipelineStep {

    @NotBlank(message = "Step name is required")
    @JsonProperty("name")
    private String name;

    @NotNull(message = "Step type is required")
    @JsonProperty("type")
    private StepType type;

    @JsonProperty("run")
    private String run;

    @JsonProperty("script")
    private String script;

    @JsonProperty("docker")
    @Valid
    private DockerConfig docker;

    @JsonProperty("push")
    @Valid
    private PushConfig push;

    @JsonProperty("deploy")
    @Valid
    private DeployConfig deploy;

    @JsonProperty("kubectl")
    @Valid
    private KubectlConfig kubectl;

    @JsonProperty("webhook")
    @Valid
    private WebhookConfig webhook;

    @JsonProperty("working_dir")
    private String workingDir;

    @JsonProperty("env")
    private Map<String, String> env;

    @JsonProperty("continue_on_error")
    private boolean continueOnError;

    @Min(value = 0, message = "Step timeout must be non-negative")
    @JsonProperty("timeout")
    private int timeout;
}
