package com.llmgateway.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("document_embedding")
public class DocumentEmbedding implements Serializable {

    @TableId(value = "embedding_id", type = IdType.INPUT)
    private String embeddingId;

    @TableField("chunk_id")
    private String chunkId;

    @TableField("document_id")
    private String documentId;

    @TableField("model_name")
    private String modelName;

    @TableField("vector")
    private byte[] vector;

    @TableField("dimension")
    private Integer dimension;

    @TableField(value = "metadata", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
