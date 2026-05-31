package com.metricplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_cdc_event")
public class SysCdcEvent extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private String eventId;

    private String connectorId;

    private String operation;

    private String databaseName;

    private String tableName;

    private String schema;

    private Map<String, Object> beforeData;

    private Map<String, Object> afterData;

    private Map<String, Object> primaryKey;

    private String lsn;

    private LocalDateTime eventTime;

    private LocalDateTime processedAt;

    private Map<String, Object> metadata;

    private String serializedData;
}
