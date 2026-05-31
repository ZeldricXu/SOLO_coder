package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("vector_index")
public class VectorIndex extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String indexId;

    private String indexName;

    private String datasourceId;

    private String tableName;

    private String columnName;

    private Integer vectorDimension;

    private String indexType;

    private String indexParams;

    private String status;

    private String indexPath;

    private LocalDateTime lastBuildTime;
}
