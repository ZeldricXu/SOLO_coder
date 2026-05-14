package com.cms.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cms_review_record")
public class ReviewRecord {

    @Id
    @Column(name = "review_id", length = 50)
    private String reviewId;

    @Column(name = "content_id", nullable = false, length = 50)
    private String contentId;

    @Column(name = "reviewer_id", length = 50)
    private String reviewerId;

    @Column(name = "reviewer_name", length = 100)
    private String reviewerName;

    @Column(name = "review_status", length = 30)
    private String reviewStatus;

    @Column(name = "review_comment", length = 500)
    private String reviewComment;

    @Column(name = "review_time", nullable = false)
    private LocalDateTime reviewTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public String getReviewId() {
        return reviewId;
    }

    public void setReviewId(String reviewId) {
        this.reviewId = reviewId;
    }

    public String getContentId() {
        return contentId;
    }

    public void setContentId(String contentId) {
        this.contentId = contentId;
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

    public String getReviewStatus() {
        return reviewStatus;
    }

    public void setReviewStatus(String reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    public String getReviewComment() {
        return reviewComment;
    }

    public void setReviewComment(String reviewComment) {
        this.reviewComment = reviewComment;
    }

    public LocalDateTime getReviewTime() {
        return reviewTime;
    }

    public void setReviewTime(LocalDateTime reviewTime) {
        this.reviewTime = reviewTime;
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
