package com.metricplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_vector_embedding")
public class SysVectorEmbedding extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private String embeddingId;

    private String indexId;

    private String originalId;

    private String originalText;

    private float[] vector;

    private Map<String, Object> metadata;

    private LocalDateTime createdAt;
}
