package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("backup_record")
public class BackupRecord extends TenantEntity {

    private String backupType;

    private String backupName;

    private String sourcePath;

    private String targetPath;

    private Long fileSize;

    private String checksum;

    private String status;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private Integer retentionDays;
}
