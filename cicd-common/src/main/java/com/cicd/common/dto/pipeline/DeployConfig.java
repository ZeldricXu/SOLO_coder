package com.cicd.common.dto.pipeline;

import com.cicd.common.enums.DeploymentStrategy;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Map;

@Data
public class DeployConfig {

    @NotBlank(message = "Deploy environment is required")
    @JsonProperty("environment")
    private String environment;

    @JsonProperty("namespace")
    private String namespace;

    @JsonProperty("app_name")
    private String appName;

    @JsonProperty("strategy")
    private DeploymentStrategy strategy;

    @JsonProperty("labels")
    private Map<String, String> labels;

    @JsonProperty("annotations")
    private Map<String, String> annotations;

    @Min(value = 0, message = "Replicas must be non-negative")
    @JsonProperty("replicas")
    private int replicas;

    @JsonProperty("image")
    private String image;

    @JsonProperty("env")
    private Map<String, String> env;

    @JsonProperty("secrets")
    private Map<String, String> secrets;

    @JsonProperty("service_port")
    private String servicePort;

    @JsonProperty("health_check_path")
    private String healthCheckPath;

    @Min(value = 0, message = "Health check timeout must be non-negative")
    @JsonProperty("health_check_timeout")
    private int healthCheckTimeout;

    @JsonProperty("canary")
    @Valid
    private CanaryConfig canary;

    @JsonProperty("blue_green")
    @Valid
    private BlueGreenConfig blueGreen;

    @JsonProperty("smoke_test")
    @Valid
    private SmokeTestConfig smokeTest;

    @JsonProperty("auto_rollback")
    @Valid
    private AutoRollbackConfig autoRollback;
}
