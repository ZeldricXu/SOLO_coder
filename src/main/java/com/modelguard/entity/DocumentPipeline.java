package com.modelguard.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.modelguard.common.BaseEntity;
import com.modelguard.common.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "document_pipeline", autoResultMap = true)
public class DocumentPipeline extends BaseEntity {

    @TableId(type = IdType.ASSIGN_ID)
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

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> config;
}
