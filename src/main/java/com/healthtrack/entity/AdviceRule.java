package com.healthtrack.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "advice_rule")
public class AdviceRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Column(name = "rule_type", nullable = false)
    private String ruleType;

    @Column(name = "priority", nullable = false)
    private String priority;

    @Column(name = "indicator_type", nullable = false)
    private String indicatorType;

    @Column(name = "condition_type", nullable = false)
    private String conditionType;

    @Column(name = "condition_operator")
    private String conditionOperator;

    @Column(name = "condition_value")
    private Double conditionValue;

    @Column(name = "condition_min")
    private Double conditionMin;

    @Column(name = "condition_max")
    private Double conditionMax;

    @Column(name = "advice_content", nullable = false, length = 2000)
    private String adviceContent;

    @Column(name = "advice_type", nullable = false)
    private String adviceType;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "rule_order", nullable = false)
    private Integer ruleOrder;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "is_global", nullable = false)
    private Boolean isGlobal;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public AdviceRule() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        this.enabled = true;
        this.isGlobal = true;
        this.ruleOrder = 0;
    }

    public enum ConditionType {
        BELOW_RANGE,
        ABOVE_RANGE,
        OUTSIDE_RANGE,
        EXACT_VALUE,
        LESS_THAN,
        GREATER_THAN,
        EQUAL,
        STATUS_ABNORMAL,
        STATUS_NORMAL,
        GOAL_LAGGING
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }
    public String getRuleType() { return ruleType; }
    public void setRuleType(String ruleType) { this.ruleType = ruleType; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getIndicatorType() { return indicatorType; }
    public void setIndicatorType(String indicatorType) { this.indicatorType = indicatorType; }
    public String getConditionType() { return conditionType; }
    public void setConditionType(String conditionType) { this.conditionType = conditionType; }
    public String getConditionOperator() { return conditionOperator; }
    public void setConditionOperator(String conditionOperator) { this.conditionOperator = conditionOperator; }
    public Double getConditionValue() { return conditionValue; }
    public void setConditionValue(Double conditionValue) { this.conditionValue = conditionValue; }
    public Double getConditionMin() { return conditionMin; }
    public void setConditionMin(Double conditionMin) { this.conditionMin = conditionMin; }
    public Double getConditionMax() { return conditionMax; }
    public void setConditionMax(Double conditionMax) { this.conditionMax = conditionMax; }
    public String getAdviceContent() { return adviceContent; }
    public void setAdviceContent(String adviceContent) { this.adviceContent = adviceContent; }
    public String getAdviceType() { return adviceType; }
    public void setAdviceType(String adviceType) { this.adviceType = adviceType; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Integer getRuleOrder() { return ruleOrder; }
    public void setRuleOrder(Integer ruleOrder) { this.ruleOrder = ruleOrder; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public Boolean getIsGlobal() { return isGlobal; }
    public void setIsGlobal(Boolean isGlobal) { this.isGlobal = isGlobal; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
