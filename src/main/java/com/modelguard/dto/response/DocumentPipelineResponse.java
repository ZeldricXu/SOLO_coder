package com.modelguard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentPipelineResponse {

    private Long id;
    private String pipelineId;
    private String name;
    private String description;
    private String sourceType;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private String embeddingModel;
    private Integer vectorDimension;
    private String status;
    private String createdBy;
    private Map<String, Object> config;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
