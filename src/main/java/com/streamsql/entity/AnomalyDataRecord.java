package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("anomaly_data_record")
public class AnomalyDataRecord extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String recordId;

    private String ruleId;

    private String datasourceId;

    private String tableName;

    private String primaryKeyValue;

    private String anomalyType;

    private String anomalyDetail;

    private Boolean marked;
}
