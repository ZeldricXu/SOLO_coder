package com.modelguard.dto.request;

import lombok.Data;
import java.util.Map;

@Data
public class DocumentTaskCreateRequest {

    private String pipelineId;

    private String fileName;

    private String filePath;

    private Long fileSize;

    private String fileType;

    private String vectorStore;

    private Map<String, Object> metadata;
}
