package com.llmgateway.document.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class ParseConfigDTO implements Serializable {

    private String pipelineId;
    private Integer chunkSize = 500;
    private Integer chunkOverlap = 50;
    private String separator = "\n";
    private String embeddingModel = "text-embedding-ada-002";
    private Boolean enableEmbedding = true;
}
