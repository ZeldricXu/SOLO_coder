package com.contractmgmt.entity;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "reminder_configs")
public class ReminderConfig {

    @Id
    @Column(name = "reminder_id")
    private String reminderId;

    @Column(name = "contract_id", nullable = false)
    private String contractId;

    @Column(name = "reminder_type", nullable = false)
    private String reminderType;

    @Column(name = "reminder_time", nullable = false)
    private LocalDate reminderTime;

    @Column(name = "reminder_channel", nullable = false)
    private String reminderChannel;

    @Column(name = "reminder_status", nullable = false)
    private String reminderStatus;

    @Column(name = "sent_time")
    private LocalDateTime sentTime;

    @Column(name = "retry_count")
    private Integer retryCount = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public ReminderConfig() {
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    public String getReminderId() {
        return reminderId;
    }

    public void setReminderId(String reminderId) {
        this.reminderId = reminderId;
    }

    public String getContractId() {
        return contractId;
    }

    public void setContractId(String contractId) {
        this.contractId = contractId;
    }

    public String getReminderType() {
        return reminderType;
    }

    public void setReminderType(String reminderType) {
        this.reminderType = reminderType;
    }

    public LocalDate getReminderTime() {
        return reminderTime;
    }

    public void setReminderTime(LocalDate reminderTime) {
        this.reminderTime = reminderTime;
    }

    public String getReminderChannel() {
        return reminderChannel;
    }

    public void setReminderChannel(String reminderChannel) {
        this.reminderChannel = reminderChannel;
    }

    public String getReminderStatus() {
        return reminderStatus;
    }

    public void setReminderStatus(String reminderStatus) {
        this.reminderStatus = reminderStatus;
    }

    public LocalDateTime getSentTime() {
        return sentTime;
    }

    public void setSentTime(LocalDateTime sentTime) {
        this.sentTime = sentTime;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
