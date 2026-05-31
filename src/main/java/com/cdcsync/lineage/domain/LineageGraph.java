package com.cdcsync.lineage.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cdcsync.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cdc_lineage_graph")
public class LineageGraph extends BaseEntity {

    private String sourceType;

    private String sourceIdentifier;

    private String sqlText;

    private String lineageJson;
}
