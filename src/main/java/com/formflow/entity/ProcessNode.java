package com.formflow.entity;

import com.formflow.enums.NodeType;
import jakarta.persistence.*;
import lombok.Data;

@Data
@Embeddable
public class ProcessNode {

    @Column(name = "node_id", nullable = false)
    private String nodeId;

    @Column(name = "node_name", nullable = false)
    private String nodeName;

    @Enumerated(EnumType.STRING)
    @Column(name = "node_type", nullable = false)
    private NodeType nodeType;

    @Column(name = "approver_role")
    private String approverRole;

    @Column(name = "approver_user_ids", length = 1000)
    private String approverUserIds;

    @Column(name = "approver_type")
    private String approverType;

    @Column(name = "approval_strategy")
    private String approvalStrategy;

    @Column(name = "condition_expression", length = 1000)
    private String conditionExpression;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @Column(name = "can_approve_self")
    private Boolean canApproveSelf = false;

    @Column(name = "can_transfer")
    private Boolean canTransfer = true;

    @Column(name = "can_add_signer")
    private Boolean canAddSigner = true;

    @Column(name = "task_due_days")
    private Integer taskDueDays;

    @Embedded
    @AttributeOverrides({
            @AttributeOverride(name = "enabled", column = @Column(name = "node_reminder_enabled")),
            @AttributeOverride(name = "triggerHoursBeforeDue", column = @Column(name = "node_reminder_trigger_hours")),
            @AttributeOverride(name = "intervalHours", column = @Column(name = "node_reminder_interval_hours")),
            @AttributeOverride(name = "maxReminders", column = @Column(name = "node_reminder_max_count")),
            @AttributeOverride(name = "escalationEnabled", column = @Column(name = "node_escalation_enabled")),
            @AttributeOverride(name = "escalationAfterHours", column = @Column(name = "node_escalation_after_hours")),
            @AttributeOverride(name = "escalationRole", column = @Column(name = "node_escalation_role")),
            @AttributeOverride(name = "escalationUserIds", column = @Column(name = "node_escalation_user_ids")),
            @AttributeOverride(name = "reminderTemplate", column = @Column(name = "node_reminder_template")),
            @AttributeOverride(name = "escalationTemplate", column = @Column(name = "node_escalation_template"))
    })
    private ReminderConfig reminderConfig;

    public ReminderConfig getReminderConfig() {
        if (this.reminderConfig == null) {
            this.reminderConfig = ReminderConfig.defaultConfig();
        }
        return this.reminderConfig;
    }

    public boolean isReminderEnabled() {
        return getReminderConfig().getEnabled() != null && getReminderConfig().getEnabled();
    }

    public int getReminderIntervalHours(int defaultValue) {
        return getReminderConfig().getIntervalHours() != null
                ? getReminderConfig().getIntervalHours()
                : defaultValue;
    }

    public int getMaxReminders(int defaultValue) {
        return getReminderConfig().getMaxReminders() != null
                ? getReminderConfig().getMaxReminders()
                : defaultValue;
    }

    public boolean isEscalationEnabled() {
        return getReminderConfig().getEscalationEnabled() != null
                && getReminderConfig().getEscalationEnabled();
    }

    public int getEscalationAfterHours(int defaultValue) {
        return getReminderConfig().getEscalationAfterHours() != null
                ? getReminderConfig().getEscalationAfterHours()
                : defaultValue;
    }

    public int getTaskDueDays(int defaultValue) {
        return this.taskDueDays != null ? this.taskDueDays : defaultValue;
    }
}
