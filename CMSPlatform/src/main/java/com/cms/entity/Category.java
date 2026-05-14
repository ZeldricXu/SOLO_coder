package com.cms.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cms_category")
public class Category {

    @Id
    @Column(name = "category_id", length = 50)
    private String categoryId;

    @Column(name = "category_name", nullable = false, length = 100)
    private String categoryName;

    @Column(name = "category_type", length = 50)
    private String categoryType;

    @Column(name = "category_parent", length = 50)
    private String categoryParent;

    @Column(name = "category_description", length = 500)
    private String categoryDescription;

    @Column(name = "category_status", length = 30)
    private String categoryStatus;

    @Column(name = "sort_order")
    private Integer sortOrder = 0;

    @Column(name = "content_count")
    private Long contentCount = 0L;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public void setCategoryName(String categoryName) {
        this.categoryName = categoryName;
    }

    public String getCategoryType() {
        return categoryType;
    }

    public void setCategoryType(String categoryType) {
        this.categoryType = categoryType;
    }

    public String getCategoryParent() {
        return categoryParent;
    }

    public void setCategoryParent(String categoryParent) {
        this.categoryParent = categoryParent;
    }

    public String getCategoryDescription() {
        return categoryDescription;
    }

    public void setCategoryDescription(String categoryDescription) {
        this.categoryDescription = categoryDescription;
    }

    public String getCategoryStatus() {
        return categoryStatus;
    }

    public void setCategoryStatus(String categoryStatus) {
        this.categoryStatus = categoryStatus;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Long getContentCount() {
        return contentCount;
    }

    public void setContentCount(Long contentCount) {
        this.contentCount = contentCount;
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
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
