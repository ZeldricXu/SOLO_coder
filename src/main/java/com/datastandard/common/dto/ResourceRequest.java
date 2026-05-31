package com.datastandard.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResourceRequest {

    private String id;
    private String type;
    private Map<String, Object> config;
    private Map<String, Object> labels;
    private String status;
}
