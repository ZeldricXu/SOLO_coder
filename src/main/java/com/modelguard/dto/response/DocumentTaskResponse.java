package com.modelguard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTaskResponse {

    private Long id;
    private String taskId;
    private String pipelineId;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private String fileType;
    private String status;
    private String phase;
    private BigDecimal progress;
    private Integer totalChunks;
    private String vectorStore;
    private String errorDetail;
    private Integer retryCount;
    private Map<String, Object> metadata;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
