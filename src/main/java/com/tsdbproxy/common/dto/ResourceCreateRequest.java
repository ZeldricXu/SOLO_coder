package com.tsdbproxy.common.dto;

import lombok.Data;

import java.util.Map;

@Data
public class ResourceCreateRequest {

    private String type;
    private Map<String, Object> config;
    private Map<String, String> labels;
}
