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
@TableName("gw_circuit_breaker_rule")
public class CircuitBreakerRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String routeId;

    private Double failureRateThreshold;

    private Double slowCallRateThreshold;

    private Long slowCallDurationThreshold;

    private Long waitDurationInOpenState;

    private Integer permittedNumberOfCallsInHalfOpenState;

    private Integer minimumNumberOfCalls;

    private Integer slidingWindowSize;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
