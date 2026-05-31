package com.tsdbproxy.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_query_plan")
public class QueryPlan extends BaseEntity {

    private String sqlText;

    private String logicalPlan;

    private String physicalPlan;

    private Long executionTimeMs;

    private String optimizationRules;
}
