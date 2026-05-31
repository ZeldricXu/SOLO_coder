package com.datastandard.common.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "slo_definitions", autoResultMap = true)
public class SloDefinition {

    @TableId(type = IdType.INPUT)
    @TableField("slo_id")
    private String sloId;

    @TableField("name")
    private String name;

    @TableField("description")
    private String description;

    @TableField("sli_type")
    private String sliType;

    @TableField("target")
    private BigDecimal target;

    @TableField("window_days")
    private Integer windowDays;

    @TableField("threshold")
    private BigDecimal threshold;

    @TableField("enabled")
    private Boolean enabled;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    @TableField("deleted")
    private Boolean deleted;
}
