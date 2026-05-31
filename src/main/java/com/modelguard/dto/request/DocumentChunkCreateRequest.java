package com.modelguard.dto.request;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class DocumentChunkCreateRequest {

    private String taskId;

    private Integer chunkIndex;

    private String content;

    private Integer wordCount;

    private Integer tokenCount;

    private List<Float> embedding;

    private Map<String, Object> metadata;
}
