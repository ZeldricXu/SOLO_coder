package com.device.platform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("backup_record")
public class BackupRecord extends BaseEntity {
    private String backupId;
    private String backupType;
    private String backupScope;
    private String storagePath;
    private Long fileSize;
    private String md5;
    private String status;
    private Instant startedAt;
    private Instant completedAt;
    private String errorDetail;
    private Integer retentionDays;
    private boolean encrypted;
}
