package com.enterprise.gateway.common.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("gw_rate_limit_rule")
public class RateLimitRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String routeId;

    private String strategy;

    private Long capacity;

    private Long refillRate;

    private Long windowSize;

    private Long permits;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
