package com.delivery.tracker.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.delivery.tracker.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("privacy_budget")
public class PrivacyBudget extends BaseEntity {

    private String userId;

    private BigDecimal epsilonRemaining;

    private BigDecimal deltaRemaining;

    private Integer totalQueries;

    private LocalDateTime lastResetAt;
}
