package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("restore_record")
public class RestoreRecord extends TenantEntity {

    private Long backupId;

    private String restoreName;

    private String sourcePath;

    private String targetPath;

    private String status;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;
}
