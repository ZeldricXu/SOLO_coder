package com.taskplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskplatform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("document_chunks")
public class DocumentChunk extends BaseEntity {

    @TableField("chunk_id")
    private String chunkId;

    @TableField("doc_id")
    private String docId;

    @TableField("chunk_index")
    private Integer chunkIndex;

    @TableField("content")
    private String content;

    @TableField("embedding")
    private byte[] embedding;

    @TableField("embedding_model")
    private String embeddingModel;

    @TableField("vector_dimension")
    private Integer vectorDimension;

    @TableField("metadata")
    private String metadata;

    @TableField("token_count")
    private Integer tokenCount;
}
