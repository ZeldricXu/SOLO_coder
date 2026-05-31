package com.cdcsync.quality.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cdcsync.common.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("cdc_quality_check_result")
public class QualityCheckResult extends BaseEntity {

    private String ruleId;

    private LocalDateTime checkTime;

    private String resultStatus;

    private String actualValue;

    private String expectedValue;

    private String errorMessage;

    private String sampleData;
}
