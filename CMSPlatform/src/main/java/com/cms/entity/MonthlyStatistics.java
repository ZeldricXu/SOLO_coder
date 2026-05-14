package com.cms.entity;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "cms_monthly_statistics")
public class MonthlyStatistics {

    @Id
    @Column(name = "stat_id", length = 50)
    private String statId;

    @Column(name = "stat_month", length = 10)
    private String statMonth;

    @Column(name = "content_count")
    private Long contentCount = 0L;

    @Column(name = "publish_count")
    private Long publishCount = 0L;

    @Column(name = "review_count")
    private Long reviewCount = 0L;

    @Column(name = "reject_count")
    private Long rejectCount = 0L;

    @Column(name = "total_view")
    private Long totalView = 0L;

    @Column(name = "total_comment")
    private Long totalComment = 0L;

    @Column(name = "total_like")
    private Long totalLike = 0L;

    @Column(name = "total_share")
    private Long totalShare = 0L;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public String getStatId() {
        return statId;
    }

    public void setStatId(String statId) {
        this.statId = statId;
    }

    public String getStatMonth() {
        return statMonth;
    }

    public void setStatMonth(String statMonth) {
        this.statMonth = statMonth;
    }

    public Long getContentCount() {
        return contentCount;
    }

    public void setContentCount(Long contentCount) {
        this.contentCount = contentCount;
    }

    public Long getPublishCount() {
        return publishCount;
    }

    public void setPublishCount(Long publishCount) {
        this.publishCount = publishCount;
    }

    public Long getReviewCount() {
        return reviewCount;
    }

    public void setReviewCount(Long reviewCount) {
        this.reviewCount = reviewCount;
    }

    public Long getRejectCount() {
        return rejectCount;
    }

    public void setRejectCount(Long rejectCount) {
        this.rejectCount = rejectCount;
    }

    public Long getTotalView() {
        return totalView;
    }

    public void setTotalView(Long totalView) {
        this.totalView = totalView;
    }

    public Long getTotalComment() {
        return totalComment;
    }

    public void setTotalComment(Long totalComment) {
        this.totalComment = totalComment;
    }

    public Long getTotalLike() {
        return totalLike;
    }

    public void setTotalLike(Long totalLike) {
        this.totalLike = totalLike;
    }

    public Long getTotalShare() {
        return totalShare;
    }

    public void setTotalShare(Long totalShare) {
        this.totalShare = totalShare;
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
