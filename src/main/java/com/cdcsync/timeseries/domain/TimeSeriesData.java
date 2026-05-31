package com.cdcsync.timeseries.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cdcsync.common.domain.BaseEntity;
import com.cdcsync.timeseries.core.Resolution;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cdc_time_series_data")
public class TimeSeriesData extends BaseEntity {

    private String configId;

    private Long metricTs;

    private Double value;

    private String tagsJson;

    private Resolution resolution;
}
