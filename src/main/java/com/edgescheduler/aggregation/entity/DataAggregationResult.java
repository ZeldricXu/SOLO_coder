package com.edgescheduler.aggregation.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.edgescheduler.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "data_aggregation_result", autoResultMap = true)
public class DataAggregationResult extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String resultId;
    private String streamId;
    private String deviceKey;
    private String aggregationType;
    private LocalDateTime windowStart;
    private LocalDateTime windowEnd;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metrics;

    private Integer sampleCount;
    private Integer uploaded;
    private LocalDateTime uploadedAt;
}
