package com.chainetl.modules.zkp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CircuitConfig {

    private String circuitId;
    private String circuitName;
    private String description;
    private Map<String, Object> verificationKey;
    private Boolean enabled;
}
