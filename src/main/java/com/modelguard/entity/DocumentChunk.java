package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "document_chunk", autoResultMap = true)
public class DocumentChunk extends BaseEntity {

    @TableField("chunk_id")
    private String chunkId;

    @TableField("task_id")
    private String taskId;

    @TableField("content")
    private String content;

    @TableField(value = "metadata", typeHandler = JacksonTypeHandler.class)
    private ObjectNode metadata;

    @TableField("embedding")
    private String embedding;

    @TableField("page_number")
    private Integer pageNumber;

    @TableField("start_index")
    private Integer startIndex;

    @TableField("end_index")
    private Integer endIndex;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
