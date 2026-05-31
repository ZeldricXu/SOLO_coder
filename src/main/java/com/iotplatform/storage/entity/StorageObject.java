package com.iotplatform.storage.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.iotplatform.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("storage_object")
public class StorageObject extends BaseEntity {

    @TableField("object_id")
    private String objectId;

    @TableField("bucket_name")
    private String bucketName;

    @TableField("object_key")
    private String objectKey;

    @TableField("object_name")
    private String objectName;

    @TableField("content_type")
    private String contentType;

    @TableField("content_length")
    private Long contentLength;

    @TableField("etag")
    private String etag;

    @TableField("provider")
    private String provider;

    @TableField("metadata")
    private String metadata;

    @TableField("tags")
    private String tags;

    @TableField("created_by")
    private String createdBy;

    public interface Provider {
        String S3 = "s3";
        String MINIO = "minio";
        String LOCAL = "local";
        String OSS = "oss";
    }
}
