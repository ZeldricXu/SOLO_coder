package com.tsdbproxy.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_quality_result")
public class QualityResult extends BaseEntity {

    private Long ruleId;

    private LocalDateTime checkTime;

    private String status;

    private String actualValue;

    private String expectedValue;

    private String errorMessage;

    private Long abnormalDataCount;
}
