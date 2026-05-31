package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "document_chunk", autoResultMap = true)
public class DocumentChunk extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String chunkId;

    private String taskId;

    private Integer chunkIndex;

    private String content;

    private Integer wordCount;

    private Integer tokenCount;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Float> embedding;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metadata;
}
