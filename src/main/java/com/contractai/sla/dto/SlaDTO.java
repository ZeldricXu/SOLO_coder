package com.contractai.sla.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class SlaDTO {

    @Data
    public static class PolicyCreateDTO {
        private String policyCode;
        private String policyName;
        private String slaType;
        private Integer priority;
        private Integer responseTime;
        private Integer resolutionTime;
        private BigDecimal warningThreshold;
        private Map<String, Object> escalationRules;
        private Map<String, Object> notificationChannels;
        private String description;
    }

    @Data
    public static class PolicyUpdateDTO {
        private String policyName;
        private String slaType;
        private Integer priority;
        private Integer responseTime;
        private Integer resolutionTime;
        private BigDecimal warningThreshold;
        private Map<String, Object> escalationRules;
        private Map<String, Object> notificationChannels;
        private Boolean enabled;
        private String description;
    }

    @Data
    public static class RecordCreateDTO {
        private Long policyId;
        private String businessType;
        private String businessId;
        private LocalDateTime startTime;
    }

    @Data
    public static class RecordAckDTO {
        private Long recordId;
        private String ackType;
        private Long operatorId;
    }

    @Data
    public static class EscalationRuleDTO {
        private Integer level;
        private String type;
        private Integer thresholdMinutes;
        private List<Long> notifiedUsers;
        private List<String> channels;
        private Boolean autoReassign;
        private Long reassignTo;
    }

    @Data
    public static class SlaSummaryDTO {
        private Long totalRecords;
        private Long pendingCount;
        private Long inProgressCount;
        private Long completedCount;
        private Long breachedCount;
        private Long warningCount;
        private BigDecimal averageResponseTime;
        private BigDecimal averageResolutionTime;
        private BigDecimal onTimeRate;
    }

    @Data
    public static class BatchMonitorDTO {
        private List<Long> recordIds;
        private Boolean includeDetails;
    }
}
