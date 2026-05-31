package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("lineage_edge")
public class LineageEdge extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String edgeId;

    private String lineageId;

    private String sourceNodeId;

    private String targetNodeId;

    private String edgeType;

    private String edgeMetadata;
}
