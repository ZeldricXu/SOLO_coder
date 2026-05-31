package com.cdcsync.cdc.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cdcsync.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cdc_change_event")
public class ChangeEvent extends BaseEntity {

    private String taskId;

    private String sourceDatabase;

    private String sourceTable;

    private String operationType;

    private String beforeData;

    private String afterData;

    private LocalDateTime eventTs;

    private Boolean processed;
}
