package com.cicd.common.dto.pipeline;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
public class KubectlConfig {

    @JsonProperty("command")
    private String command;

    @JsonProperty("args")
    private List<String> args;

    @JsonProperty("namespace")
    private String namespace;

    @JsonProperty("kubeconfig_secret")
    private String kubeconfigSecret;

    @JsonProperty("file")
    private String file;
}
