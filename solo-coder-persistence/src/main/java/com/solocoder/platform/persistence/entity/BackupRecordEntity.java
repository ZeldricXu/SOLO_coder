package com.solocoder.platform.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_backup_record")
public class BackupRecordEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String backupId;
    private String sourcePath;
    private String targetPath;
    private String status;
    private Long fileSize;
    private String checksum;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    private String errorMessage;

    @TableLogic
    private Integer deleted;
}
