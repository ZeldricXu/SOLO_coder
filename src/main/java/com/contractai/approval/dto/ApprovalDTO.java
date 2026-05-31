package com.contractai.approval.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class ApprovalDTO {

    @Data
    public static class RuleCreateDTO {
        private String ruleCode;
        private String ruleName;
        private String ruleType;
        private String businessType;
        private Integer priority;
        private String conditionExpression;
        private String approvalStrategy;
        private Integer approverCount;
        private BigDecimal approvalPercentage;
        private Map<String, Object> approverConfig;
        private List<Long> ccConfig;
        private Map<String, Object> timeoutConfig;
        private String description;
    }

    @Data
    public static class RuleUpdateDTO {
        private String ruleName;
        private String ruleType;
        private String businessType;
        private Integer priority;
        private String conditionExpression;
        private String approvalStrategy;
        private Integer approverCount;
        private BigDecimal approvalPercentage;
        private Map<String, Object> approverConfig;
        private List<Long> ccConfig;
        private Map<String, Object> timeoutConfig;
        private Boolean enabled;
        private String description;
    }

    @Data
    public static class ProcessStartDTO {
        private String businessType;
        private String businessId;
        private String title;
        private Map<String, Object> formData;
        private Map<String, Object> variables;
        private List<Long> approverList;
        private List<Long> ccList;
        private Long startedBy;
        private String approvalStrategy;
        private Integer timeoutMinutes;
    }

    @Data
    public static class ProcessStartResultDTO {
        private Long processId;
        private String processNo;
        private String status;
        private Integer totalStages;
        private Integer currentStage;
        private List<ApprovalTaskDTO> currentTasks;
        private LocalDateTime startedAt;
    }

    @Data
    public static class ApprovalTaskDTO {
        private Long taskId;
        private Long approverId;
        private String approverName;
        private String status;
        private String action;
        private String comment;
        private LocalDateTime assignedAt;
        private LocalDateTime actedAt;
    }

    @Data
    public static class ApproveDTO {
        private Long taskId;
        private Long approverId;
        private String action;
        private String comment;
        private Map<String, Object> signatures;
        private Long transferTo;
        private Long delegateTo;
    }

    @Data
    public static class StageConfigDTO {
        private String stageName;
        private String approvalStrategy;
        private String signType;
        private List<Long> approverIds;
        private Integer approverCount;
        private BigDecimal approvalPercentage;
        private String conditionExpression;
    }

    @Data
    public static class ConditionEvaluationDTO {
        private String expression;
        private Map<String, Object> formData;
        private Map<String, Object> variables;
        private Long starterId;
        private String businessType;
    }

    @Data
    public static class DynamicApproverDTO {
        private String businessType;
        private Map<String, Object> formData;
        private Map<String, Object> variables;
        private Long starterId;
        private String strategy;
    }

    @Data
    public static class CancelProcessDTO {
        private Long processId;
        private Long operatorId;
        private String reason;
    }

    @Data
    public static class AddSignDTO {
        private Long processId;
        private Long stageId;
        private List<Long> approverIds;
        private String signType;
        private Long operatorId;
        private String reason;
    }
}
