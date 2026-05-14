package com.cms.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cms_review_reminder")
public class ReviewReminder {

    @Id
    @Column(name = "reminder_id", length = 50)
    private String reminderId;

    @Column(name = "content_id", nullable = false, length = 50)
    private String contentId;

    @Column(name = "content_title", length = 200)
    private String contentTitle;

    @Column(name = "reviewer_id", length = 50)
    private String reviewerId;

    @Column(name = "reviewer_name", length = 100)
    private String reviewerName;

    @Column(name = "reminder_type", length = 50)
    private String reminderType;

    @Column(name = "urgency_level", length = 30)
    private String urgencyLevel;

    @Column(name = "reminder_message", length = 500)
    private String reminderMessage;

    @Column(name = "reminder_status", length = 30)
    private String reminderStatus;

    @Column(name = "reminder_time")
    private LocalDateTime reminderTime;

    @Column(name = "read_time")
    private LocalDateTime readTime;

    @Column(name = "reminder_frequency_minutes")
    private Integer reminderFrequencyMinutes;

    @Column(name = "reminder_count")
    private Integer reminderCount;

    @Column(name = "next_reminder_time")
    private LocalDateTime nextReminderTime;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public String getReminderId() {
        return reminderId;
    }

    public void setReminderId(String reminderId) {
        this.reminderId = reminderId;
    }

    public String getContentId() {
        return contentId;
    }

    public void setContentId(String contentId) {
        this.contentId = contentId;
    }

    public String getContentTitle() {
        return contentTitle;
    }

    public void setContentTitle(String contentTitle) {
        this.contentTitle = contentTitle;
    }

    public String getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(String reviewerId) {
        this.reviewerId = reviewerId;
    }

    public String getReviewerName() {
        return reviewerName;
    }

    public void setReviewerName(String reviewerName) {
        this.reviewerName = reviewerName;
    }

    public String getReminderType() {
        return reminderType;
    }

    public void setReminderType(String reminderType) {
        this.reminderType = reminderType;
    }

    public String getUrgencyLevel() {
        return urgencyLevel;
    }

    public void setUrgencyLevel(String urgencyLevel) {
        this.urgencyLevel = urgencyLevel;
    }

    public String getReminderMessage() {
        return reminderMessage;
    }

    public void setReminderMessage(String reminderMessage) {
        this.reminderMessage = reminderMessage;
    }

    public String getReminderStatus() {
        return reminderStatus;
    }

    public void setReminderStatus(String reminderStatus) {
        this.reminderStatus = reminderStatus;
    }

    public LocalDateTime getReminderTime() {
        return reminderTime;
    }

    public void setReminderTime(LocalDateTime reminderTime) {
        this.reminderTime = reminderTime;
    }

    public LocalDateTime getReadTime() {
        return readTime;
    }

    public void setReadTime(LocalDateTime readTime) {
        this.readTime = readTime;
    }

    public Integer getReminderFrequencyMinutes() {
        return reminderFrequencyMinutes;
    }

    public void setReminderFrequencyMinutes(Integer reminderFrequencyMinutes) {
        this.reminderFrequencyMinutes = reminderFrequencyMinutes;
    }

    public Integer getReminderCount() {
        return reminderCount;
    }

    public void setReminderCount(Integer reminderCount) {
        this.reminderCount = reminderCount;
    }

    public LocalDateTime getNextReminderTime() {
        return nextReminderTime;
    }

    public void setNextReminderTime(LocalDateTime nextReminderTime) {
        this.nextReminderTime = nextReminderTime;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.reminderTime == null) {
            this.reminderTime = LocalDateTime.now();
        }
        if (this.reminderCount == null) {
            this.reminderCount = 1;
        }
        if (this.reminderStatus == null) {
            this.reminderStatus = "unread";
        }
    }
}
