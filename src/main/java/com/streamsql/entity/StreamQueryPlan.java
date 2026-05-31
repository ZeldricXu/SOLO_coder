package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stream_query_plan")
public class StreamQueryPlan extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String planId;

    private String queryName;

    private String originalSql;

    private String logicalPlan;

    private String physicalPlan;

    private String executionConfig;

    private String status;
}
