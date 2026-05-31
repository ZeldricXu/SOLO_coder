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
@TableName("sys_cdc_connector")
public class SysCdcConnector extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private String connectorId;

    private String connectorName;

    private String sourceType;

    private Map<String, Object> sourceConfig;

    private Map<String, Object> outputConfig;

    private String outputType;

    private String status;

    private String currentLsn;

    private LocalDateTime startedAt;

    private LocalDateTime stoppedAt;

    private Long processedEvents;

    private Long lastEventAt;
}
