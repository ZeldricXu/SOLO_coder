package com.contractmgmt.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "contract")
public class ContractConfig {

    private Reminder reminder = new Reminder();
    private Approval approval = new Approval();

    public Reminder getReminder() {
        return reminder;
    }

    public void setReminder(Reminder reminder) {
        this.reminder = reminder;
    }

    public Approval getApproval() {
        return approval;
    }

    public void setApproval(Approval approval) {
        this.approval = approval;
    }

    public static class Reminder {
        private Integer advanceDays = 15;

        public Integer getAdvanceDays() {
            return advanceDays;
        }

        public void setAdvanceDays(Integer advanceDays) {
            this.advanceDays = advanceDays;
        }
    }

    public static class Approval {
        private List<String> defaultApprovers = new ArrayList<>();
        private Timeout timeout = new Timeout();
        private ApprovalFlow approvalFlow = new ApprovalFlow();
        private ExecutionCheck executionCheck = new ExecutionCheck();

        public List<String> getDefaultApprovers() {
            return defaultApprovers;
        }

        public void setDefaultApprovers(List<String> defaultApprovers) {
            this.defaultApprovers = defaultApprovers;
        }

        public Timeout getTimeout() {
            return timeout;
        }

        public void setTimeout(Timeout timeout) {
            this.timeout = timeout;
        }

        public ApprovalFlow getApprovalFlow() {
            return approvalFlow;
        }

        public void setApprovalFlow(ApprovalFlow approvalFlow) {
            this.approvalFlow = approvalFlow;
        }

        public ExecutionCheck getExecutionCheck() {
            return executionCheck;
        }

        public void setExecutionCheck(ExecutionCheck executionCheck) {
            this.executionCheck = executionCheck;
        }
    }

    public static class Timeout {
        private Integer normalHours = 24;
        private Integer urgentHours = 4;
        private Integer criticalHours = 1;
        private Boolean enabled = true;

        public Integer getNormalHours() {
            return normalHours;
        }

        public void setNormalHours(Integer normalHours) {
            this.normalHours = normalHours;
        }

        public Integer getUrgentHours() {
            return urgentHours;
        }

        public void setUrgentHours(Integer urgentHours) {
            this.urgentHours = urgentHours;
        }

        public Integer getCriticalHours() {
            return criticalHours;
        }

        public void setCriticalHours(Integer criticalHours) {
            this.criticalHours = criticalHours;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getTimeoutByUrgency(String urgency) {
            if (urgency == null) {
                return normalHours;
            }
            switch (urgency.toLowerCase()) {
                case "urgent":
                    return urgentHours;
                case "critical":
                    return criticalHours;
                case "normal":
                default:
                    return normalHours;
            }
        }
    }

    public static class ExecutionCheck {
        private Integer activeHours = 1;
        private Integer normalHours = 6;
        private Integer inactiveHours = 24;

        public Integer getActiveHours() {
            return activeHours;
        }

        public void setActiveHours(Integer activeHours) {
            this.activeHours = activeHours;
        }

        public Integer getNormalHours() {
            return normalHours;
        }

        public void setNormalHours(Integer normalHours) {
            this.normalHours = normalHours;
        }

        public Integer getInactiveHours() {
            return inactiveHours;
        }

        public void setInactiveHours(Integer inactiveHours) {
            this.inactiveHours = inactiveHours;
        }

        public Integer getCheckIntervalByActivity(String activityLevel) {
            if (activityLevel == null) {
                return normalHours;
            }
            switch (activityLevel.toLowerCase()) {
                case "active":
                    return activeHours;
                case "inactive":
                    return inactiveHours;
                case "normal":
                default:
                    return normalHours;
            }
        }
    }

    public static class ApprovalFlow {
        private List<FlowConfig> flows = new ArrayList<>();
        private String defaultFlow = "default";

        public List<FlowConfig> getFlows() {
            return flows;
        }

        public void setFlows(List<FlowConfig> flows) {
            this.flows = flows;
        }

        public String getDefaultFlow() {
            return defaultFlow;
        }

        public void setDefaultFlow(String defaultFlow) {
            this.defaultFlow = defaultFlow;
        }
    }

    public static class FlowConfig {
        private String name;
        private String description;
        private List<String> approvers = new ArrayList<>();
        private String condition = "default";
        private Boolean enabled = true;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public List<String> getApprovers() {
            return approvers;
        }

        public void setApprovers(List<String> approvers) {
            this.approvers = approvers;
        }

        public String getCondition() {
            return condition;
        }

        public void setCondition(String condition) {
            this.condition = condition;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }
}
