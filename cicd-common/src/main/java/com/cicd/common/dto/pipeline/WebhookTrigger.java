package com.cicd.common.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.List;

@Data
public class WebhookTrigger {

    @NotBlank(message = "Webhook trigger type is required")
    @JsonProperty("type")
    private String type;

    @JsonProperty("branches")
    private List<String> branches;

    @JsonProperty("paths")
    private List<String> paths;

    @JsonProperty("ignore_paths")
    private List<String> ignorePaths;

    @JsonProperty("branch_pattern")
    private String branchPattern;
}
