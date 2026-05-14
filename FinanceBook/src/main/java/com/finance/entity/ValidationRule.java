package com.finance.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Entity
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "validation_rules")
public class ValidationRule {

    @Id
    @Column(name = "rule_id", nullable = false, length = 50)
    private String ruleId;

    @Column(name = "rule_name", nullable = false, length = 100)
    private String ruleName;

    @Column(name = "transaction_type_code", nullable = false, length = 50)
    private String transactionTypeCode;

    @Column(name = "rule_type", nullable = false, length = 50)
    private String ruleType;

    @Column(name = "rule_config", columnDefinition = "TEXT")
    private String ruleConfig;

    @Column(name = "rule_priority", nullable = false)
    private Integer rulePriority;

    @Column(name = "rule_status", nullable = false, length = 20)
    private String ruleStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
