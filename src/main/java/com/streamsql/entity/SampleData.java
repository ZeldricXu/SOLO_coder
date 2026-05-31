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
@TableName("sample_data")
public class SampleData extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String sampleId;

    private String datasourceId;

    private String schemaName;

    private String tableName;

    private String sampleData;

    private LocalDateTime sampleTime;
}
