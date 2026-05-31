package com.tsdbproxy.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_lineage_graph")
public class LineageGraph extends BaseEntity {

    private String sourceTable;

    private String sourceColumn;

    private String targetTable;

    private String targetColumn;

    private String transformType;

    private String sqlTemplate;
}
