package com.cicd.common.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import java.util.Map;

@Data
public class WebhookConfig {

    @NotBlank(message = "Webhook URL is required")
    @JsonProperty("url")
    private String url;

    @JsonProperty("method")
    private String method;

    @JsonProperty("headers")
    private Map<String, String> headers;

    @JsonProperty("body")
    private String body;

    @JsonProperty("secret")
    private String secret;

    @Min(value = 0, message = "Webhook timeout must be non-negative")
    @JsonProperty("timeout")
    private int timeout;
}
