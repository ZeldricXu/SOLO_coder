package com.llmgateway.document.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("document")
public class Document implements Serializable {

    @TableId(value = "document_id", type = IdType.INPUT)
    private String documentId;

    @TableField("title")
    private String title;

    @TableField("file_name")
    private String fileName;

    @TableField("file_type")
    private String fileType;

    @TableField("file_size")
    private Long fileSize;

    @TableField("content_hash")
    private String contentHash;

    @TableField("storage_path")
    private String storagePath;

    @TableField("charset")
    private String charset;

    @TableField("language")
    private String language;

    @TableField("status")
    private String status;

    @TableField(value = "metadata", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> metadata;

    @TableField("created_by")
    private String createdBy;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
