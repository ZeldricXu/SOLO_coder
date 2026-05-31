package com.metricplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_entity")
public class SysEntity extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    private String type;

    private String status;

    private Map<String, Object> attributes;

    private Map<String, Object> labels;

    private Map<String, Object> config;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
