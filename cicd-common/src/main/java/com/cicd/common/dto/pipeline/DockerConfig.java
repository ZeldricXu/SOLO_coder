package com.cicd.common.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class DockerConfig {

    @JsonProperty("image")
    private String image;

    @JsonProperty("dockerfile")
    private String dockerfile;

    @JsonProperty("context")
    private String context;

    @JsonProperty("tags")
    private List<String> tags;

    @JsonProperty("build_args")
    private Map<String, String> buildArgs;

    @JsonProperty("pull")
    private boolean pull;

    @JsonProperty("no_cache")
    private boolean noCache;

    @JsonProperty("command")
    private String[] command;

    @JsonProperty("entrypoint")
    private String entrypoint;

    @JsonProperty("volumes")
    private Map<String, String> volumes;
}
