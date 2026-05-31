package com.solocoder.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("files")
public class FileEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String fileName;

    private Long fileSize;

    private String filePath;

    private String storageClass;

    private String lifecyclePolicy;

    private String status;

    private String metadata;

    private Instant createdAt;

    private Instant updatedAt;

    private Instant archivedAt;

    private Instant expiresAt;
}
