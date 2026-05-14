package com.cms.entity;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "cms_content")
public class Content {

    @Id
    @Column(name = "content_id", length = 50)
    private String contentId;

    @Column(name = "content_title", nullable = false, length = 200)
    private String contentTitle;

    @Column(name = "content_type", length = 50)
    private String contentType;

    @Lob
    @Column(name = "content_body", nullable = false)
    private String contentBody;

    @Column(name = "content_status", length = 30)
    private String contentStatus;

    @Column(name = "content_author", length = 100)
    private String contentAuthor;

    @Column(name = "content_category", length = 50)
    private String contentCategory;

    @ElementCollection
    @CollectionTable(name = "cms_content_tags", joinColumns = @JoinColumn(name = "content_id"))
    @Column(name = "content_tag")
    private List<String> contentTags = new ArrayList<>();

    @Column(name = "template_id", length = 50)
    private String templateId;

    @Column(name = "reviewer_id", length = 50)
    private String reviewerId;

    @Column(name = "review_comment", length = 500)
    private String reviewComment;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "unpublished_at")
    private LocalDateTime unpublishedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

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

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getContentBody() {
        return contentBody;
    }

    public void setContentBody(String contentBody) {
        this.contentBody = contentBody;
    }

    public String getContentStatus() {
        return contentStatus;
    }

    public void setContentStatus(String contentStatus) {
        this.contentStatus = contentStatus;
    }

    public String getContentAuthor() {
        return contentAuthor;
    }

    public void setContentAuthor(String contentAuthor) {
        this.contentAuthor = contentAuthor;
    }

    public String getContentCategory() {
        return contentCategory;
    }

    public void setContentCategory(String contentCategory) {
        this.contentCategory = contentCategory;
    }

    public List<String> getContentTags() {
        return contentTags;
    }

    public void setContentTags(List<String> contentTags) {
        this.contentTags = contentTags;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getReviewerId() {
        return reviewerId;
    }

    public void setReviewerId(String reviewerId) {
        this.reviewerId = reviewerId;
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public void setReviewComment(String reviewComment) {
        this.reviewComment = reviewComment;
    }

    public LocalDateTime getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(LocalDateTime reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public LocalDateTime getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(LocalDateTime publishedAt) {
        this.publishedAt = publishedAt;
    }

    public LocalDateTime getUnpublishedAt() {
        return unpublishedAt;
    }

    public void setUnpublishedAt(LocalDateTime unpublishedAt) {
        this.unpublishedAt = unpublishedAt;
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
