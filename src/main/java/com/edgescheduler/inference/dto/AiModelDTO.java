package com.edgescheduler.inference.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class AiModelDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String modelId;

    @NotEmpty(message = "modelName cannot be empty")
    private String modelName;

    private String modelVersion;
    private String framework;
    private String modelType;
    private Map<String, Object> modelSpec;
    private Long modelSize;
    private String downloadUrl;
    private String md5Checksum;
    private String status;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
