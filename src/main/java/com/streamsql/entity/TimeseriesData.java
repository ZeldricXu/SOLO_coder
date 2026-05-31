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
@TableName("timeseries_data")
public class TimeseriesData extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String dataId;

    private String metricName;

    private LocalDateTime timestamp;

    private Double metricValue;

    private String tags;

    private String resolution;

    private Boolean compressed;

    private byte[] compressedData;
}
