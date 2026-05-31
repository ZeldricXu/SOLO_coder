package com.edgescheduler.aggregation.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.edgescheduler.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "data_stream", autoResultMap = true)
public class DataStream extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String streamId;
    private String streamName;
    private String deviceKey;
    private String dataType;
    private String aggregationType;
    private String aggregationWindow;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> fieldsConfig;

    private Integer enabled;
    private LocalDateTime lastAggregatedAt;

    public interface DataType {
        String TELEMETRY = "telemetry";
        String EVENT = "event";
        String ATTRIBUTE = "attribute";
    }

    public interface AggregationType {
        String NONE = "none";
        String AVG = "avg";
        String SUM = "sum";
        String COUNT = "count";
        String MIN = "min";
        String MAX = "max";
        String FIRST = "first";
        String LAST = "last";
    }
}
