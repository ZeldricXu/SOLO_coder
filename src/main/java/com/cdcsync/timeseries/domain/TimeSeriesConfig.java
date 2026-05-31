package com.cdcsync.timeseries.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cdcsync.common.domain.BaseEntity;
import com.cdcsync.timeseries.core.CompressionAlgorithm;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cdc_time_series_config")
public class TimeSeriesConfig extends BaseEntity {

    private String name;

    private String metricName;

    private CompressionAlgorithm compressionAlgorithm;

    private Integer rawRetentionDays;

    private Integer downsample1hRetentionDays;

    private Integer downsample1dRetentionDays;

    private Boolean enabled;
}
