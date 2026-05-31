package com.smartflow.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.smartflow.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_process_line")
public class ProcessLine extends BaseEntity {

    private Long definitionId;
    private String lineCode;
    private Long fromNodeId;
    private Long toNodeId;
    private String conditionExpression;
    private Integer sortOrder;
    private String style;
}
