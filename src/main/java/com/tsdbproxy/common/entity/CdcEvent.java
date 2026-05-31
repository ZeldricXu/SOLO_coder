package com.tsdbproxy.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_cdc_event")
public class CdcEvent extends BaseEntity {

    private Long taskId;

    private String eventType;

    private String database;

    private String tableName;

    private String beforeData;

    private String afterData;

    private String binlogPosition;

    private LocalDateTime eventTime;
}
