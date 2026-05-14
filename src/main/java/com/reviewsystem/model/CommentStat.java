package com.reviewsystem.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "comment_stats")
@EntityListeners(AuditingEntityListener.class)
public class CommentStat {

    @Id
    @Column(name = "stat_id", length = 50)
    private String statId;

    @Column(name = "content_id", nullable = false, length = 50)
    private String contentId;

    @Column(name = "stat_date", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate statDate;

    @Column(name = "total_comments")
    private Integer totalComments = 0;

    @Column(name = "published_comments")
    private Integer publishedComments = 0;

    @Column(name = "rejected_comments")
    private Integer rejectedComments = 0;

    @Column(name = "pending_comments")
    private Integer pendingComments = 0;

    @Column(name = "avg_quality_score")
    private Double avgQualityScore = 0.0;

    @Column(name = "avg_sentiment_score")
    private Double avgSentimentScore = 0.0;

    @Column(name = "positive_count")
    private Integer positiveCount = 0;

    @Column(name = "negative_count")
    private Integer negativeCount = 0;

    @Column(name = "report_count")
    private Integer reportCount = 0;

    @Column(name = "total_likes")
    private Integer totalLikes = 0;

    @Column(name = "total_replies")
    private Integer totalReplies = 0;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime updatedAt;

    public CommentStat() {}

    public String getStatId() {
        return statId;
    }

    public void setStatId(String statId) {
        this.statId = statId;
    }

    public String getContentId() {
        return contentId;
    }

    public void setContentId(String contentId) {
        this.contentId = contentId;
    }

    public LocalDate getStatDate() {
        return statDate;
    }

    public void setStatDate(LocalDate statDate) {
        this.statDate = statDate;
    }

    public Integer getTotalComments() {
        return totalComments;
    }

    public void setTotalComments(Integer totalComments) {
        this.totalComments = totalComments;
    }

    public Integer getPublishedComments() {
        return publishedComments;
    }

    public void setPublishedComments(Integer publishedComments) {
        this.publishedComments = publishedComments;
    }

    public Integer getRejectedComments() {
        return rejectedComments;
    }

    public void setRejectedComments(Integer rejectedComments) {
        this.rejectedComments = rejectedComments;
    }

    public Integer getPendingComments() {
        return pendingComments;
    }

    public void setPendingComments(Integer pendingComments) {
        this.pendingComments = pendingComments;
    }

    public Double getAvgQualityScore() {
        return avgQualityScore;
    }

    public void setAvgQualityScore(Double avgQualityScore) {
        this.avgQualityScore = avgQualityScore;
    }

    public Double getAvgSentimentScore() {
        return avgSentimentScore;
    }

    public void setAvgSentimentScore(Double avgSentimentScore) {
        this.avgSentimentScore = avgSentimentScore;
    }

    public Integer getPositiveCount() {
        return positiveCount;
    }

    public void setPositiveCount(Integer positiveCount) {
        this.positiveCount = positiveCount;
    }

    public Integer getNegativeCount() {
        return negativeCount;
    }

    public void setNegativeCount(Integer negativeCount) {
        this.negativeCount = negativeCount;
    }

    public Integer getReportCount() {
        return reportCount;
    }

    public void setReportCount(Integer reportCount) {
        this.reportCount = reportCount;
    }

    public Integer getTotalLikes() {
        return totalLikes;
    }

    public void setTotalLikes(Integer totalLikes) {
        this.totalLikes = totalLikes;
    }

    public Integer getTotalReplies() {
        return totalReplies;
    }

    public void setTotalReplies(Integer totalReplies) {
        this.totalReplies = totalReplies;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
