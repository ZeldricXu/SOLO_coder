package com.supplychain.common.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.*;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalWorkflowConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private String workflowId;

    private String workflowName;

    private String orderType;

    private String description;

    private boolean enabled;

    private int version;

    private List<ApprovalStep> steps;

    private Map<String, Object> conditions;

    private Map<String, Object> notifications;

    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ApprovalStep implements Serializable {
        private static final long serialVersionUID = 1L;

        private String stepId;

        private String stepName;

        private int stepOrder;

        private String approverRole;

        private List<String> approverUsers;

        private ApprovalType approvalType;

        private double minAmount;

        private double maxAmount;

        private int timeoutMinutes;

        private boolean skippable;

        private String skipCondition;

        private String onApproveAction;

        private String onRejectAction;

        private String statusBefore;

        private String statusAfter;

        private Map<String, Object> metadata;
    }

    public enum ApprovalType {
        SINGLE("single", "单人审批"),
        OR("or", "或签 - 任意一人审批"),
        AND("and", "并签 - 所有人审批"),
        MAJORITY("majority", "多数通过");

        private final String code;
        private final String description;

        ApprovalType(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }

    public static Map<String, ApprovalWorkflowConfig> getDefaultWorkflows() {
        Map<String, ApprovalWorkflowConfig> workflows = new HashMap<>();

        workflows.put("standard_purchase", ApprovalWorkflowConfig.builder()
                .workflowId("wf_standard_001")
                .workflowName("标准采购审批流程")
                .orderType("purchase")
                .description("标准采购订单审批流程")
                .enabled(true)
                .version(1)
                .steps(Arrays.asList(
                        ApprovalStep.builder()
                                .stepId("step_1")
                                .stepName("主管审批")
                                .stepOrder(1)
                                .approverRole("supervisor")
                                .approverUsers(Arrays.asList("user_001", "user_002"))
                                .approvalType(ApprovalType.OR)
                                .minAmount(0)
                                .maxAmount(50000)
                                .timeoutMinutes(120)
                                .skippable(false)
                                .statusBefore("pending_approval")
                                .statusAfter("confirmed")
                                .onApproveAction("confirm_order")
                                .onRejectAction("reject_order")
                                .build(),
                        ApprovalStep.builder()
                                .stepId("step_2")
                                .stepName("经理审批")
                                .stepOrder(2)
                                .approverRole("manager")
                                .approverUsers(Arrays.asList("user_101", "user_102"))
                                .approvalType(ApprovalType.SINGLE)
                                .minAmount(50000)
                                .maxAmount(500000)
                                .timeoutMinutes(240)
                                .skippable(false)
                                .statusBefore("pending_approval")
                                .statusAfter("confirmed")
                                .onApproveAction("confirm_order")
                                .onRejectAction("reject_order")
                                .build(),
                        ApprovalStep.builder()
                                .stepId("step_3")
                                .stepName("总监审批")
                                .stepOrder(3)
                                .approverRole("director")
                                .approverUsers(Arrays.asList("user_201"))
                                .approvalType(ApprovalType.SINGLE)
                                .minAmount(500000)
                                .maxAmount(Double.MAX_VALUE)
                                .timeoutMinutes(480)
                                .skippable(false)
                                .statusBefore("pending_approval")
                                .statusAfter("confirmed")
                                .onApproveAction("confirm_order")
                                .onRejectAction("reject_order")
                                .build()
                ))
                .conditions(Map.of("autoApproval", false, "requireAttachment", true))
                .notifications(Map.of("timeoutNotify", true, "approveNotify", true, "rejectNotify", true))
                .build());

        workflows.put("urgent_purchase", ApprovalWorkflowConfig.builder()
                .workflowId("wf_urgent_001")
                .workflowName("紧急采购审批流程")
                .orderType("urgent_purchase")
                .description("紧急采购订单审批流程 - 快速通道")
                .enabled(true)
                .version(1)
                .steps(Arrays.asList(
                        ApprovalStep.builder()
                                .stepId("urgent_step_1")
                                .stepName("紧急审批")
                                .stepOrder(1)
                                .approverRole("manager")
                                .approverUsers(Arrays.asList("user_101", "user_102", "user_201"))
                                .approvalType(ApprovalType.OR)
                                .minAmount(0)
                                .maxAmount(Double.MAX_VALUE)
                                .timeoutMinutes(30)
                                .skippable(false)
                                .statusBefore("pending_approval")
                                .statusAfter("confirmed")
                                .onApproveAction("confirm_order")
                                .onRejectAction("reject_order")
                                .metadata(Map.of("priority", "critical", "escalation", true))
                                .build()
                ))
                .conditions(Map.of("autoApproval", false, "requireAttachment", false, "skipFirstStep", true))
                .notifications(Map.of("timeoutNotify", true, "approveNotify", true, "rejectNotify", true, "escalationNotify", true))
                .build());

        workflows.put("low_priority_purchase", ApprovalWorkflowConfig.builder()
                .workflowId("wf_low_001")
                .workflowName("低优先级采购审批流程")
                .orderType("low_priority_purchase")
                .description("低优先级采购订单审批流程 - 宽松审批")
                .enabled(true)
                .version(1)
                .steps(Arrays.asList(
                        ApprovalStep.builder()
                                .stepId("low_step_1")
                                .stepName("普通审批")
                                .stepOrder(1)
                                .approverRole("supervisor")
                                .approverUsers(Arrays.asList("user_001", "user_002", "user_003"))
                                .approvalType(ApprovalType.OR)
                                .minAmount(0)
                                .maxAmount(20000)
                                .timeoutMinutes(480)
                                .skippable(true)
                                .skipCondition("amount < 5000")
                                .statusBefore("pending_approval")
                                .statusAfter("confirmed")
                                .onApproveAction("confirm_order")
                                .onRejectAction("reject_order")
                                .build(),
                        ApprovalStep.builder()
                                .stepId("low_step_2")
                                .stepName("经理审批")
                                .stepOrder(2)
                                .approverRole("manager")
                                .approverUsers(Arrays.asList("user_101"))
                                .approvalType(ApprovalType.SINGLE)
                                .minAmount(20000)
                                .maxAmount(Double.MAX_VALUE)
                                .timeoutMinutes(720)
                                .skippable(false)
                                .statusBefore("pending_approval")
                                .statusAfter("confirmed")
                                .onApproveAction("confirm_order")
                                .onRejectAction("reject_order")
                                .build()
                ))
                .conditions(Map.of("autoApproval", true, "autoApprovalThreshold", 5000, "requireAttachment", false))
                .notifications(Map.of("timeoutNotify", false, "approveNotify", true, "rejectNotify", true))
                .build());

        workflows.put("emergency_purchase", ApprovalWorkflowConfig.builder()
                .workflowId("wf_emergency_001")
                .workflowName("应急采购审批流程")
                .orderType("emergency_purchase")
                .description("应急采购订单审批流程 - 极速通道")
                .enabled(true)
                .version(1)
                .steps(Arrays.asList(
                        ApprovalStep.builder()
                                .stepId("emergency_step_1")
                                .stepName("应急审批")
                                .stepOrder(1)
                                .approverRole("director")
                                .approverUsers(Arrays.asList("user_201", "user_301"))
                                .approvalType(ApprovalType.OR)
                                .minAmount(0)
                                .maxAmount(Double.MAX_VALUE)
                                .timeoutMinutes(15)
                                .skippable(false)
                                .statusBefore("pending_approval")
                                .statusAfter("confirmed")
                                .onApproveAction("confirm_order")
                                .onRejectAction("reject_order")
                                .metadata(Map.of("priority", "emergency", "escalation", true, "ceoEscalation", true))
                                .build()
                ))
                .conditions(Map.of("autoApproval", false, "requireAttachment", false, "immediateProcessing", true))
                .notifications(Map.of("timeoutNotify", true, "approveNotify", true, "rejectNotify", true, "escalationNotify", true, "smsNotify", true))
                .build());

        return workflows;
    }

    public static ApprovalWorkflowConfig getWorkflowByOrderType(String orderType) {
        Map<String, ApprovalWorkflowConfig> workflows = getDefaultWorkflows();

        if (orderType != null) {
            for (Map.Entry<String, ApprovalWorkflowConfig> entry : workflows.entrySet()) {
                if (orderType.equalsIgnoreCase(entry.getKey()) ||
                    orderType.toLowerCase().contains(entry.getKey().replace("_purchase", ""))) {
                    return entry.getValue();
                }
            }

            if (orderType.toLowerCase().contains("urgent") || orderType.toLowerCase().contains("紧急")) {
                return workflows.get("urgent_purchase");
            }
            if (orderType.toLowerCase().contains("low") || orderType.toLowerCase().contains("低")) {
                return workflows.get("low_priority_purchase");
            }
            if (orderType.toLowerCase().contains("emergency") || orderType.toLowerCase().contains("应急")) {
                return workflows.get("emergency_purchase");
            }
        }

        return workflows.get("standard_purchase");
    }

    public List<ApprovalStep> getStepsForAmount(double amount) {
        if (steps == null) {
            return Collections.emptyList();
        }

        List<ApprovalStep> applicableSteps = new ArrayList<>();
        for (ApprovalStep step : steps) {
            if (amount >= step.getMinAmount() && amount < step.getMaxAmount()) {
                applicableSteps.add(step);
            }
        }

        applicableSteps.sort(Comparator.comparingInt(ApprovalStep::getStepOrder));
        return applicableSteps;
    }

    public ApprovalStep getCurrentStep(int currentStepIndex) {
        if (steps == null || currentStepIndex < 0 || currentStepIndex >= steps.size()) {
            return null;
        }
        return steps.get(currentStepIndex);
    }

    public boolean hasNextStep(int currentStepIndex) {
        return steps != null && currentStepIndex < steps.size() - 1;
    }

    public ApprovalStep getNextStep(int currentStepIndex) {
        if (!hasNextStep(currentStepIndex)) {
            return null;
        }
        return steps.get(currentStepIndex + 1);
    }
}
