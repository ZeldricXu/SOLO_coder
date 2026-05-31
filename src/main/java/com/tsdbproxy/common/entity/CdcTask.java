package com.tsdbproxy.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_cdc_task")
public class CdcTask extends BaseEntity {

    private String name;

    private Long datasourceId;

    private String tableName;

    private String outputType;

    private String outputConfig;

    private String status;

    private String lastBinlogPosition;

    private LocalDateTime lastProcessTime;

    private Long processedEvents;
}
