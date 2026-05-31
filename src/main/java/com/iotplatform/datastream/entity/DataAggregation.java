package com.iotplatform.datastream.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("data_aggregation")
public class DataAggregation implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("aggregation_id")
    private String aggregationId;

    @TableField("device_id")
    private String deviceId;

    @TableField("stream_id")
    private String streamId;

    @TableField("window_start")
    private LocalDateTime windowStart;

    @TableField("window_end")
    private LocalDateTime windowEnd;

    @TableField("aggregation_type")
    private String aggregationType;

    @TableField("metric_name")
    private String metricName;

    @TableField("metric_value")
    private BigDecimal metricValue;

    @TableField("record_count")
    private Integer recordCount;

    @TableField("metadata")
    private String metadata;

    @TableField("uploaded")
    private Boolean uploaded;

    @TableField("uploaded_at")
    private LocalDateTime uploadedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    public interface AggregationType {
        String SUM = "sum";
        String AVG = "avg";
        String COUNT = "count";
        String MIN = "min";
        String MAX = "max";
        String FIRST = "first";
        String LAST = "last";
    }
}
