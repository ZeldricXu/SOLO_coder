package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("core_entity")
public class CoreEntity extends BaseEntity {

    private String entId;
    private String type;
    private String status;
    private Map<String, Object> attributes;
}
