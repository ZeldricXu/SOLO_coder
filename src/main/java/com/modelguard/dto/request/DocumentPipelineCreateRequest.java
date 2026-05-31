package com.modelguard.dto.request;

import lombok.Data;
import java.util.Map;

@Data
public class DocumentPipelineCreateRequest {

    private String name;

    private String description;

    private String sourceType;

    private Integer chunkSize;

    private Integer chunkOverlap;

    private String embeddingModel;

    private Integer vectorDimension;

    private String createdBy;

    private Map<String, Object> config;
}
