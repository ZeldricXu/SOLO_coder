package com.solocoder.dns.sidecar.model;

import lombok.Data;
import java.io.Serializable;

@Data
public class SidecarInjectionPolicy implements Serializable {
    private String policyId;
    private String namespace;
    private String selector;
    private Boolean enabled;
    private String sidecarImage;
    private Double cpuRequest;
    private Double cpuLimit;
    private Double memoryRequest;
    private Double memoryLimit;
    private String injectionMode;
}
