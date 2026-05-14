package com.cms.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cms_content_type_config")
public class ContentTypeConfig {

    @Id
    @Column(name = "type_id", length = 50)
    private String typeId;

    @Column(name = "type_code", nullable = false, unique = true, length = 50)
    private String typeCode;

    @Column(name = "type_name", nullable = false, length = 100)
    private String typeName;

    @Column(name = "type_description", length = 500)
    private String typeDescription;

    @Column(name = "default_template_id", length = 50)
    private String defaultTemplateId;

    @Column(name = "default_category", length = 50)
    private String defaultCategory;

    @Column(name = "allowed_tags")
    private String allowedTags;

    @Column(name = "review_required", nullable = false)
    private Boolean reviewRequired = true;

    @Column(name = "publish_approval_required", nullable = false)
    private Boolean publishApprovalRequired = true;

    @Column(name = "default_urgency_level", length = 30)
    private String defaultUrgencyLevel;

    @Column(name = "default_importance_level", length = 30)
    private String defaultImportanceLevel;

    @Column(name = "review_frequency_minutes")
    private Integer reviewFrequencyMinutes;

    @Column(name = "warning_offset_minutes")
    private Integer warningOffsetMinutes;

    @Column(name = "max_title_length", nullable = false)
    private Integer maxTitleLength = 200;

    @Column(name = "max_body_length")
    private Long maxBodyLength;

    @Column(name = "content_status", length = 30)
    private String contentStatus;

    @Column(name = "is_active", nullable = false)
    private Boolean isActive = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_by", length = 50)
    private String createdBy;

    @Column(name = "updated_by", length = 50)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public String getTypeId() {
        return typeId;
    }

    public void setTypeId(String typeId) {
        this.typeId = typeId;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    public String getTypeName() {
        return typeName;
    }

    public void setTypeName(String typeName) {
        this.typeName = typeName;
    }

    public String getTypeDescription() {
        return typeDescription;
    }

    public void setTypeDescription(String typeDescription) {
        this.typeDescription = typeDescription;
    }

    public String getDefaultTemplateId() {
        return defaultTemplateId;
    }

    public void setDefaultTemplateId(String defaultTemplateId) {
        this.defaultTemplateId = defaultTemplateId;
    }

    public String getDefaultCategory() {
        return defaultCategory;
    }

    public void setDefaultCategory(String defaultCategory) {
        this.defaultCategory = defaultCategory;
    }

    public String getAllowedTags() {
        return allowedTags;
    }

    public void setAllowedTags(String allowedTags) {
        this.allowedTags = allowedTags;
    }

    public Boolean getReviewRequired() {
        return reviewRequired;
    }

    public void setReviewRequired(Boolean reviewRequired) {
        this.reviewRequired = reviewRequired;
    }

    public Boolean getPublishApprovalRequired() {
        return publishApprovalRequired;
    }

    public void setPublishApprovalRequired(Boolean publishApprovalRequired) {
        this.publishApprovalRequired = publishApprovalRequired;
    }

    public String getDefaultUrgencyLevel() {
        return defaultUrgencyLevel;
    }

    public void setDefaultUrgencyLevel(String defaultUrgencyLevel) {
        this.defaultUrgencyLevel = defaultUrgencyLevel;
    }

    public String getDefaultImportanceLevel() {
        return defaultImportanceLevel;
    }

    public void setDefaultImportanceLevel(String defaultImportanceLevel) {
        this.defaultImportanceLevel = defaultImportanceLevel;
    }

    public Integer getReviewFrequencyMinutes() {
        return reviewFrequencyMinutes;
    }

    public void setReviewFrequencyMinutes(Integer reviewFrequencyMinutes) {
        this.reviewFrequencyMinutes = reviewFrequencyMinutes;
    }

    public Integer getWarningOffsetMinutes() {
        return warningOffsetMinutes;
    }

    public void setWarningOffsetMinutes(Integer warningOffsetMinutes) {
        this.warningOffsetMinutes = warningOffsetMinutes;
    }

    public Integer getMaxTitleLength() {
        return maxTitleLength;
    }

    public void setMaxTitleLength(Integer maxTitleLength) {
        this.maxTitleLength = maxTitleLength;
    }

    public Long getMaxBodyLength() {
        return maxBodyLength;
    }

    public void setMaxBodyLength(Long maxBodyLength) {
        this.maxBodyLength = maxBodyLength;
    }

    public String getContentStatus() {
        return contentStatus;
    }

    public void setContentStatus(String contentStatus) {
        this.contentStatus = contentStatus;
    }

    public Boolean getActive() {
        return isActive;
    }

    public void setActive(Boolean active) {
        isActive = active;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
        if (this.reviewRequired == null) {
            this.reviewRequired = true;
        }
        if (this.publishApprovalRequired == null) {
            this.publishApprovalRequired = true;
        }
        if (this.isActive == null) {
            this.isActive = true;
        }
        if (this.sortOrder == null) {
            this.sortOrder = 0;
        }
        if (this.maxTitleLength == null) {
            this.maxTitleLength = 200;
        }
        if (this.defaultUrgencyLevel == null) {
            this.defaultUrgencyLevel = "normal";
        }
        if (this.defaultImportanceLevel == null) {
            this.defaultImportanceLevel = "normal";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
