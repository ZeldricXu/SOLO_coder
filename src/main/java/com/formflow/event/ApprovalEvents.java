package com.formflow.event;

import com.formflow.dto.ApprovalProcessRequest;
import com.formflow.dto.ApprovalProcessResponse;
import com.formflow.entity.ApprovalTask;
import com.formflow.entity.ProcessInstance;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

public class ApprovalEvents {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalSubmittedEvent {
        private String taskId;
        private String instanceId;
        private String formId;
        private String nodeId;
        private String nodeName;
        private String approvalResult;
        private String approvalComment;
        private String approverId;
        private String approverName;
        private String submitterId;
        private String submitterName;
        private LocalDateTime submittedTime;
        private Map<String, Object> variables;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessTransitionEvent {
        private String instanceId;
        private String formId;
        private String fromNodeId;
        private String fromNodeName;
        private String toNodeId;
        private String toNodeName;
        private String transitionReason;
        private Map<String, Object> variables;
        private LocalDateTime transitionTime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessCompletedEvent {
        private String instanceId;
        private String formId;
        private boolean approved;
        private String finalApprovalResult;
        private String endNodeId;
        private String endNodeName;
        private Map<String, Object> variables;
        private LocalDateTime completedTime;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalTaskCreatedEvent {
        private String taskId;
        private String instanceId;
        private String nodeId;
        private String nodeName;
        private String approverId;
        private String approverName;
        private String formId;
        private String formTitle;
        private String submitterId;
        private String submitterName;
        private LocalDateTime assignedTime;
        private LocalDateTime dueTime;
        private Integer priority;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ReminderTriggeredEvent {
        private String taskId;
        private String instanceId;
        private String approverId;
        private String formTitle;
        private int reminderCount;
        private LocalDateTime dueTime;
        private LocalDateTime reminderTime;
        private String message;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalEscalatedEvent {
        private String originalTaskId;
        private String escalationTaskId;
        private String instanceId;
        private String originalApproverId;
        private String escalationApproverId;
        private String escalationReason;
        private int originalReminderCount;
        private LocalDateTime escalationTime;
    }

    public static ApprovalSubmittedEvent fromApprovalTask(ApprovalTask task,
                                                          ApprovalProcessRequest request,
                                                          Map<String, Object> variables) {
        return ApprovalSubmittedEvent.builder()
                .taskId(task.getTaskId())
                .instanceId(task.getInstanceId())
                .formId(task.getFormId())
                .nodeId(task.getNodeId())
                .nodeName(task.getNodeName())
                .approvalResult(request.getApprovalResult())
                .approvalComment(request.getApprovalComment())
                .approverId(request.getApproverId())
                .approverName(request.getApproverName())
                .submitterId(task.getSubmitterId())
                .submitterName(task.getSubmitterName())
                .submittedTime(LocalDateTime.now())
                .variables(variables)
                .build();
    }

    public static ApprovalTaskCreatedEvent fromApprovalTask(ApprovalTask task) {
        return ApprovalTaskCreatedEvent.builder()
                .taskId(task.getTaskId())
                .instanceId(task.getInstanceId())
                .nodeId(task.getNodeId())
                .nodeName(task.getNodeName())
                .approverId(task.getApproverId())
                .approverName(task.getApproverName())
                .formId(task.getFormId())
                .formTitle(task.getFormTitle())
                .submitterId(task.getSubmitterId())
                .submitterName(task.getSubmitterName())
                .assignedTime(task.getAssignedTime())
                .dueTime(task.getDueTime())
                .priority(task.getPriority())
                .build();
    }
}
