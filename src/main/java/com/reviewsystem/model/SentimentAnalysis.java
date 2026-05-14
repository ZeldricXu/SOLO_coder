package com.reviewsystem.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "sentiment_analyses")
@EntityListeners(AuditingEntityListener.class)
public class SentimentAnalysis {

    @Id
    @Column(name = "sentiment_id", length = 50)
    private String sentimentId;

    @Column(name = "comment_id", nullable = false, length = 50)
    private String commentId;

    @Column(name = "sentiment_type", length = 20)
    private String sentimentType;

    @Column(name = "sentiment_score")
    private Double sentimentScore;

    @ElementCollection
    @CollectionTable(name = "sentiment_keywords", joinColumns = @JoinColumn(name = "sentiment_id"))
    @Column(name = "keyword")
    private List<String> sentimentKeywords;

    @Column(name = "positive_score")
    private Double positiveScore;

    @Column(name = "negative_score")
    private Double negativeScore;

    @Column(name = "neutral_score")
    private Double neutralScore;

    @CreatedDate
    @Column(name = "analyzed_at", nullable = false, updatable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime analyzedAt;

    public SentimentAnalysis() {}

    public String getSentimentId() {
        return sentimentId;
    }

    public void setSentimentId(String sentimentId) {
        this.sentimentId = sentimentId;
    }

    public String getCommentId() {
        return commentId;
    }

    public void setCommentId(String commentId) {
        this.commentId = commentId;
    }

    public String getSentimentType() {
        return sentimentType;
    }

    public void setSentimentType(String sentimentType) {
        this.sentimentType = sentimentType;
    }

    public Double getSentimentScore() {
        return sentimentScore;
    }

    public void setSentimentScore(Double sentimentScore) {
        this.sentimentScore = sentimentScore;
    }

    public List<String> getSentimentKeywords() {
        return sentimentKeywords;
    }

    public void setSentimentKeywords(List<String> sentimentKeywords) {
        this.sentimentKeywords = sentimentKeywords;
    }

    public Double getPositiveScore() {
        return positiveScore;
    }

    public void setPositiveScore(Double positiveScore) {
        this.positiveScore = positiveScore;
    }

    public Double getNegativeScore() {
        return negativeScore;
    }

    public void setNegativeScore(Double negativeScore) {
        this.negativeScore = negativeScore;
    }

    public Double getNeutralScore() {
        return neutralScore;
    }

    public void setNeutralScore(Double neutralScore) {
        this.neutralScore = neutralScore;
    }

    public LocalDateTime getAnalyzedAt() {
        return analyzedAt;
    }

    public void setAnalyzedAt(LocalDateTime analyzedAt) {
        this.analyzedAt = analyzedAt;
    }
}
