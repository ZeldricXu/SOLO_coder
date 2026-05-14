package com.cms.dto;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;
import java.util.Map;

public class PublishExecuteRequest {

    @NotBlank(message = "内容ID不能为空")
    private String contentId;

    private String publishChannel;

    private LocalDateTime scheduleTime;

    private Map<String, String> publishConfig;

    private String publisherId;

    private String publisherName;

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
}
