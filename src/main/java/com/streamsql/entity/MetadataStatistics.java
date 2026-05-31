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
@TableName("metadata_statistics")
public class MetadataStatistics extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String statId;

    private String datasourceId;

    private String schemaName;

    private String tableName;

    private String columnName;

    private String statType;

    private Double statValue;

    private String statJson;

    private LocalDateTime statTime;
}
