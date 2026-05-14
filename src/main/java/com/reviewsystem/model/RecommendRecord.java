package com.reviewsystem.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "recommend_records")
@EntityListeners(AuditingEntityListener.class)
public class RecommendRecord {

    @Id
    @Column(name = "recommend_id", length = 50)
    private String recommendId;

    @Column(name = "comment_id", nullable = false, length = 50)
    private String commentId;

    @Column(name = "content_id", length = 50)
    private String contentId;

    @Column(name = "recommend_type", length = 20)
    private String recommendType = "quality";

    @Column(name = "recommend_score")
    private Integer recommendScore;

    @Column(name = "recommend_position")
    private Integer recommendPosition;

    @Column(name = "quality_factor")
    private Integer qualityFactor;

    @Column(name = "heat_factor")
    private Integer heatFactor;

    @Column(name = "time_factor")
    private Integer timeFactor;

    @Column(name = "sentiment_factor")
    private Integer sentimentFactor;

    @CreatedDate
    @Column(name = "calculated_at", nullable = false, updatable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime calculatedAt;

    public RecommendRecord() {}

    public String getRecommendId() {
        return recommendId;
    }

    public void setRecommendId(String recommendId) {
        this.recommendId = recommendId;
    }

    public String getCommentId() {
        return commentId;
    }

    public void setCommentId(String commentId) {
        this.commentId = commentId;
    }

    public String getContentId() {
        return contentId;
    }

    public void setContentId(String contentId) {
        this.contentId = contentId;
    }

    public String getRecommendType() {
        return recommendType;
    }

    public void setRecommendType(String recommendType) {
        this.recommendType = recommendType;
    }

    public Integer getRecommendScore() {
        return recommendScore;
    }

    public void setRecommendScore(Integer recommendScore) {
        this.recommendScore = recommendScore;
    }

    public Integer getRecommendPosition() {
        return recommendPosition;
    }

    public void setRecommendPosition(Integer recommendPosition) {
        this.recommendPosition = recommendPosition;
    }

    public Integer getQualityFactor() {
        return qualityFactor;
    }

    public void setQualityFactor(Integer qualityFactor) {
        this.qualityFactor = qualityFactor;
    }

    public Integer getHeatFactor() {
        return heatFactor;
    }

    public void setHeatFactor(Integer heatFactor) {
        this.heatFactor = heatFactor;
    }

    public Integer getTimeFactor() {
        return timeFactor;
    }

    public void setTimeFactor(Integer timeFactor) {
        this.timeFactor = timeFactor;
    }

    public Integer getSentimentFactor() {
        return sentimentFactor;
    }

    public void setSentimentFactor(Integer sentimentFactor) {
        this.sentimentFactor = sentimentFactor;
    }

    public LocalDateTime getCalculatedAt() {
        return calculatedAt;
    }

    public void setCalculatedAt(LocalDateTime calculatedAt) {
        this.calculatedAt = calculatedAt;
    }
}
