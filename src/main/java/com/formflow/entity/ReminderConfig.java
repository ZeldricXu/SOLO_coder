package com.formflow.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Data;

@Data
@Embeddable
public class ReminderConfig {

    @Column(name = "reminder_enabled")
    private Boolean enabled = true;

    @Column(name = "reminder_trigger_hours_before_due")
    private Integer triggerHoursBeforeDue = 24;

    @Column(name = "reminder_interval_hours")
    private Integer intervalHours = 24;

    @Column(name = "reminder_max_count")
    private Integer maxReminders = 3;

    @Column(name = "escalation_enabled")
    private Boolean escalationEnabled = true;

    @Column(name = "escalation_after_hours")
    private Integer escalationAfterHours = 48;

    @Column(name = "escalation_role")
    private String escalationRole;

    @Column(name = "escalation_user_ids", length = 1000)
    private String escalationUserIds;

    @Column(name = "reminder_template")
    private String reminderTemplate;

    @Column(name = "escalation_template")
    private String escalationTemplate;

    public static ReminderConfig defaultConfig() {
        ReminderConfig config = new ReminderConfig();
        config.setEnabled(true);
        config.setTriggerHoursBeforeDue(24);
        config.setIntervalHours(24);
        config.setMaxReminders(3);
        config.setEscalationEnabled(true);
        config.setEscalationAfterHours(48);
        config.setEscalationRole("manager");
        return config;
    }

    public static ReminderConfig disabled() {
        ReminderConfig config = new ReminderConfig();
        config.setEnabled(false);
        config.setEscalationEnabled(false);
        return config;
    }

    public static ReminderConfig urgentConfig() {
        ReminderConfig config = new ReminderConfig();
        config.setEnabled(true);
        config.setTriggerHoursBeforeDue(4);
        config.setIntervalHours(4);
        config.setMaxReminders(5);
        config.setEscalationEnabled(true);
        config.setEscalationAfterHours(12);
        config.setEscalationRole("director");
        return config;
    }
}
