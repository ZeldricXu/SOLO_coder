package com.taskplatform.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.taskplatform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("documents")
public class Document extends BaseEntity {

    @TableField("doc_id")
    private String docId;

    @TableField("title")
    private String title;

    @TableField("content")
    private String content;

    @TableField("file_path")
    private String filePath;

    @TableField("file_type")
    private String fileType;

    @TableField("file_size")
    private Long fileSize;

    @TableField("checksum")
    private String checksum;

    @TableField("parse_status")
    private String parseStatus;

    @TableField("chunk_count")
    private Integer chunkCount;

    @TableField("metadata")
    private String metadata;

    @TableField("tags")
    private String tags;

    @TableField("created_by")
    private String createdBy;

    @TableField("processed_at")
    private java.time.LocalDateTime processedAt;
}
