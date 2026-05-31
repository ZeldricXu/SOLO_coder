package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("lineage_node")
public class LineageNode extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String nodeId;

    private String lineageId;

    private String nodeType;

    private String nodeName;

    private String nodeMetadata;
}
