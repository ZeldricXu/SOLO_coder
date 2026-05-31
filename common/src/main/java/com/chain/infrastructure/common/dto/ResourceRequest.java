package com.chain.infrastructure.common.dto;

import lombok.Data;
import java.util.Map;

@Data
public class ResourceRequest {

    private String type;

    private Map<String, Object> config;

    private Map<String, String> labels;

    private String namespace;
}
