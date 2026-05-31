package com.contractai.ticket.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public class TicketDTO {

    @Data
    public static class TicketCreateDTO {
        private String title;
        private String description;
        private String ticketType;
        private Integer priority;
        private String source;
        private String category;
        private List<String> tags;
        private List<Long> requiredSkills;
        private Long slaPolicyId;
        private Long parentId;
        private Map<String, Object> formData;
        private Long createdBy;
        private Long assigneeId;
        private String assigneeGroup;
    }

    @Data
    public static class TicketUpdateDTO {
        private String title;
        private String description;
        private String ticketType;
        private Integer priority;
        private String status;
        private String category;
        private List<String> tags;
        private List<Long> requiredSkills;
        private Map<String, Object> formData;
    }

    @Data
    public static class TicketAssignDTO {
        private Long ticketId;
        private Long assigneeId;
        private String assignmentType;
        private String assignmentReason;
        private Long assignedBy;
    }

    @Data
    public static class TicketAutoAssignDTO {
        private Long ticketId;
        private String strategyType;
        private Long strategyId;
        private List<Long> candidateEmployeeIds;
    }

    @Data
    public static class AssignmentCandidateDTO {
        private Long employeeId;
        private String employeeName;
        private String department;
        private String position;
        private BigDecimal matchScore;
        private BigDecimal workloadScore;
        private BigDecimal efficiencyScore;
        private BigDecimal finalScore;
        private Integer openTicketsCount;
        private Integer capacity;
        private List<String> matchedSkills;
        private List<String> missingSkills;
    }

    @Data
    public static class AssignmentResultDTO {
        private Long ticketId;
        private String ticketNo;
        private String ticketTitle;
        private Long assignedToId;
        private String assignedToName;
        private String assignmentStrategy;
        private BigDecimal matchScore;
        private BigDecimal workloadScore;
        private BigDecimal finalScore;
        private List<AssignmentCandidateDTO> allCandidates;
        private String assignmentReason;
    }

    @Data
    public static class StrategyCreateDTO {
        private String strategyCode;
        private String strategyName;
        private String strategyType;
        private List<String> ticketTypes;
        private BigDecimal skillMatchWeight;
        private BigDecimal loadBalanceWeight;
        private BigDecimal efficiencyWeight;
        private Map<String, Object> config;
        private String description;
    }

    @Data
    public static class StrategyUpdateDTO {
        private String strategyName;
        private String strategyType;
        private List<String> ticketTypes;
        private BigDecimal skillMatchWeight;
        private BigDecimal loadBalanceWeight;
        private BigDecimal efficiencyWeight;
        private Map<String, Object> config;
        private Boolean enabled;
        private String description;
    }

    @Data
    public static class WorkloadRecalculateDTO {
        private List<Long> employeeIds;
        private Boolean recalculateAll;
    }

    @Data
    public static class TicketStatusUpdateDTO {
        private Long ticketId;
        private String status;
        private Long operatorId;
        private String comment;
        private Map<String, Object> resolutionData;
    }

    @Data
    public static class BatchAssignDTO {
        private List<Long> ticketIds;
        private String strategyType;
        private Long strategyId;
    }
}
