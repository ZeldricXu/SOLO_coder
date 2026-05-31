package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cdc_capture_task")
public class CdcCaptureTask extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String taskId;

    private String taskName;

    private String datasourceId;

    private String schemaName;

    private String tableNames;

    private String status;

    private String offsetInfo;

    private String outputType;

    private String outputConfig;

    private LocalDateTime lastEventTime;
}
