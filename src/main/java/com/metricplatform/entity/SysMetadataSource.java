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
@TableName("sys_metadata_source")
public class SysMetadataSource extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private String sourceId;

    private String sourceName;

    private String sourceType;

    private Map<String, Object> connectionConfig;

    private String status;

    private LocalDateTime lastScanAt;

    private Long scanInterval;
}
