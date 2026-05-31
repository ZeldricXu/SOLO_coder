package com.tsdbproxy.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_column_schema")
public class ColumnSchema extends BaseEntity {

    private Long tableId;

    private String columnName;

    private String columnType;

    private String columnComment;

    private Integer isNullable;

    private Integer isPrimaryKey;

    private Integer ordinalPosition;

    private String minValue;

    private String maxValue;

    private Long distinctCount;

    private Long nullCount;
}
