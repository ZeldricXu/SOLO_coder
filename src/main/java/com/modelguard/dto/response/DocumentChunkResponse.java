package com.modelguard.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunkResponse {

    private Long id;
    private String chunkId;
    private String taskId;
    private Integer chunkIndex;
    private String content;
    private Integer wordCount;
    private Integer tokenCount;
    private List<Float> embedding;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
}
