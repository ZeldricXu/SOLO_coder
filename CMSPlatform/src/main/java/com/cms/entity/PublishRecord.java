package com.cms.entity;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(name = "cms_publish_record")
public class PublishRecord {

    @Id
    @Column(name = "publish_id", length = 50)
    private String publishId;

    @Column(name = "content_id", nullable = false, length = 50)
    private String contentId;

    @Column(name = "publish_channel", length = 50)
    private String publishChannel;

    @Column(name = "publish_time")
    private LocalDateTime publishTime;

    @Column(name = "publish_status", length = 30)
    private String publishStatus;

    @Column(name = "schedule_time")
    private LocalDateTime scheduleTime;

    @ElementCollection
    @CollectionTable(name = "cms_publish_config", joinColumns = @JoinColumn(name = "publish_id"))
    @MapKeyColumn(name = "config_key")
    @Column(name = "config_value")
    private Map<String, String> publishConfig = new HashMap<>();

    @Column(name = "publisher_id", length = 50)
    private String publisherId;

    @Column(name = "publisher_name", length = 100)
    private String publisherName;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

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

    public String getPublishChannel() {
        return publishChannel;
    }

    public void setPublishChannel(String publishChannel) {
        this.publishChannel = publishChannel;
    }

    public LocalDateTime getPublishTime() {
        return publishTime;
    }

    public void setPublishTime(LocalDateTime publishTime) {
        this.publishTime = publishTime;
    }

    public String getPublishStatus() {
        return publishStatus;
    }

    public void setPublishStatus(String publishStatus) {
        this.publishStatus = publishStatus;
    }

    public LocalDateTime getScheduleTime() {
        return scheduleTime;
    }

    public void setScheduleTime(LocalDateTime scheduleTime) {
        this.scheduleTime = scheduleTime;
    }

    public Map<String, String> getPublishConfig() {
        return publishConfig;
    }

    public void setPublishConfig(Map<String, String> publishConfig) {
        this.publishConfig = publishConfig;
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

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
