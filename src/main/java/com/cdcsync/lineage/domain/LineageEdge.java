package com.cdcsync.lineage.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cdcsync.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cdc_lineage_edge")
public class LineageEdge extends BaseEntity {

    private String graphId;

    private String sourceTable;

    private String sourceColumn;

    private String targetTable;

    private String targetColumn;

    private String transformation;
}
