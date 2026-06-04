package com.cicd.common.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;

@Data
public class PushConfig {

    @NotBlank(message = "Push registry is required")
    @JsonProperty("registry")
    private String registry;

    @JsonProperty("repository")
    private String repository;

    @JsonProperty("tags")
    private List<String> tags;

    @JsonProperty("username")
    private String username;

    @JsonProperty("password_secret")
    private String passwordSecret;

    @JsonProperty("type")
    private String type;
}
