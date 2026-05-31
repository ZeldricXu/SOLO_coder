package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("storage_content")
public class StorageContentEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("content_id")
    private String contentId;

    @TableField("content_hash")
    private String contentHash;

    @TableField("storage_type")
    private String storageType;

    @TableField("network")
    private String network;

    @TableField("size")
    private Long size;

    @TableField("mime_type")
    private String mimeType;

    @TableField("pin_status")
    private String pinStatus;

    @TableField("pin_location")
    private String pinLocation;

    @TableField("replication_count")
    private Integer replicationCount;

    @TableField("expire_time")
    private LocalDateTime expireTime;

    @TableField("metadata")
    private String metadata;

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
