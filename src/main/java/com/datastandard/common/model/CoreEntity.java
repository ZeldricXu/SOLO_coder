package com.datastandard.common.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.datastandard.common.handler.JsonMapTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "core_entities", autoResultMap = true)
public class CoreEntity {

    @TableId(type = IdType.INPUT)
    private String id;

    @TableField("type")
    private String type;

    @TableField("status")
    private String status;

    @TableField(value = "attributes", typeHandler = JsonMapTypeHandler.class)
    private Map<String, Object> attributes;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("deleted")
    private Boolean deleted;
}
