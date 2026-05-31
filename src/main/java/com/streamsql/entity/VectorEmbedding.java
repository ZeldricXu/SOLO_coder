package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("vector_embedding")
public class VectorEmbedding extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String embeddingId;

    private String indexId;

    private String dataKey;

    private byte[] vector;

    private String metadata;
}
