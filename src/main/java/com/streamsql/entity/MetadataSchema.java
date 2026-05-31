package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("metadata_schema")
public class MetadataSchema extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String schemaId;

    private String datasourceId;

    private String schemaName;

    private String tableName;

    private String columnName;

    private String dataType;

    private Boolean nullable;

    private Boolean primaryKey;

    private String columnComment;
}
