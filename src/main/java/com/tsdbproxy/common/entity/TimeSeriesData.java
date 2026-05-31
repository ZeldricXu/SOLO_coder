package com.tsdbproxy.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_time_series_data")
public class TimeSeriesData extends BaseEntity {

    private String metric;

    private String tags;

    private LocalDateTime timestamp;

    private Double value;

    private String resolution;

    private String compressionType;
}
