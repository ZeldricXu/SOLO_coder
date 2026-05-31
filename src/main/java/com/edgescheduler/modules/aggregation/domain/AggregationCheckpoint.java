package com.edgescheduler.modules.aggregation.domain;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.edgescheduler.domain.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("aggregation_checkpoint")
public class AggregationCheckpoint extends BaseEntity {

    @TableField("checkpoint_id")
    private String checkpointId;

    @TableField("device_id")
    private String deviceId;

    @TableField("aggregation_type")
    private String aggregationType;

    @TableField("time_window")
    private String timeWindow;

    @TableField(value = "buffer_snapshot", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private List<Map<String, Object>> bufferSnapshot;

    @TableField("window_start")
    private LocalDateTime windowStart;
}
