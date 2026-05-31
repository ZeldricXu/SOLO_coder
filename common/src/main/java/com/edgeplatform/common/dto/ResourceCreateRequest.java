package com.edgeplatform.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

@Data
public class ResourceCreateRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String type;
    private Map<String, Object> config;
    private Map<String, String> labels;
}
