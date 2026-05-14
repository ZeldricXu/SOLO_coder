package com.cms.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cms_publish_warning")
public class PublishWarning {

    @Id
    @Column(name = "warning_id", length = 50)
    private String warningId;

    @Column(name = "publish_id", length = 50)
    private String publishId;

    @Column(name = "content_id", nullable = false, length = 50)
    private String contentId;

    @Column(name = "content_title", length = 200)
    private String contentTitle;

    @Column(name = "publisher_id", length = 50)
    private String publisherId;

    @Column(name = "publisher_name", length = 100)
    private String publisherName;

    @Column(name = "warning_type", length = 50)
    private String warningType;

    @Column(name = "importance_level", length = 30)
    private String importanceLevel;

    @Column(name = "warning_message", length = 500)
    private String warningMessage;

    @Column(name = "warning_status", length = 30)
    private String warningStatus;

    @Column(name = "warning_time")
    private LocalDateTime warningTime;

    @Column(name = "acknowledged_time")
    private LocalDateTime acknowledgedTime;

    @Column(name = "acknowledged_by_id", length = 50)
    private String acknowledgedById;

    @Column(name = "acknowledged_by_name", length = 100)
    private String acknowledgedByName;

    @Column(name = "scheduled_publish_time")
    private LocalDateTime scheduledPublishTime;

    @Column(name = "warning_offset_minutes")
    private Integer warningOffsetMinutes;

    @Column(name = "publish_channel", length = 50)
    private String publishChannel;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public String getWarningId() {
        return warningId;
    }

    public void setWarningId(String warningId) {
        this.warningId = warningId;
    }

    public String getPublishId() {
        return publishId;
    }

    public void setPublishId(String publishId) {
        this.publishId = publishId;
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

    public String getPublisherId() {
        return publisherId;
    }

    public void setPublisherId(String publisherId) {
        this.publisherId = publisherId;
    }

    public String getPublisherName() {
        return publisherName;
    }

    public void setPublisherName(String publisherName) {
        this.publisherName = publisherName;
    }

    public String getWarningType() {
        return warningType;
    }

    public void setWarningType(String warningType) {
        this.warningType = warningType;
    }

    public String getImportanceLevel() {
        return importanceLevel;
    }

    public void setImportanceLevel(String importanceLevel) {
        this.importanceLevel = importanceLevel;
    }

    public String getWarningMessage() {
        return warningMessage;
    }

    public void setWarningMessage(String warningMessage) {
        this.warningMessage = warningMessage;
    }

    public String getWarningStatus() {
        return warningStatus;
    }

    public void setWarningStatus(String warningStatus) {
        this.warningStatus = warningStatus;
    }

    public LocalDateTime getWarningTime() {
        return warningTime;
    }

    public void setWarningTime(LocalDateTime warningTime) {
        this.warningTime = warningTime;
    }

    public LocalDateTime getAcknowledgedTime() {
        return acknowledgedTime;
    }

    public void setAcknowledgedTime(LocalDateTime acknowledgedTime) {
        this.acknowledgedTime = acknowledgedTime;
    }

    public String getAcknowledgedById() {
        return acknowledgedById;
    }

    public void setAcknowledgedById(String acknowledgedById) {
        this.acknowledgedById = acknowledgedById;
    }

    public String getAcknowledgedByName() {
        return acknowledgedByName;
    }

    public void setAcknowledgedByName(String acknowledgedByName) {
        this.acknowledgedByName = acknowledgedByName;
    }

    public LocalDateTime getScheduledPublishTime() {
        return scheduledPublishTime;
    }

    public void setScheduledPublishTime(LocalDateTime scheduledPublishTime) {
        this.scheduledPublishTime = scheduledPublishTime;
    }

    public Integer getWarningOffsetMinutes() {
        return warningOffsetMinutes;
    }

    public void setWarningOffsetMinutes(Integer warningOffsetMinutes) {
        this.warningOffsetMinutes = warningOffsetMinutes;
    }

    public String getPublishChannel() {
        return publishChannel;
    }

    public void setPublishChannel(String publishChannel) {
        this.publishChannel = publishChannel;
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
        if (this.warningTime == null) {
            this.warningTime = LocalDateTime.now();
        }
        if (this.warningStatus == null) {
            this.warningStatus = "pending";
        }
    }
}
