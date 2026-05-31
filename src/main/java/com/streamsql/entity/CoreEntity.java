package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("core_entity")
public class CoreEntity extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String type;

    private String status;

    private String attributes;
}
