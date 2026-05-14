package com.reviewsystem.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "quality_evaluations")
@EntityListeners(AuditingEntityListener.class)
public class QualityEvaluation {

    @Id
    @Column(name = "evaluation_id", length = 50)
    private String evaluationId;

    @Column(name = "comment_id", nullable = false, length = 50)
    private String commentId;

    @Column(name = "quality_score")
    private Integer qualityScore;

    @Column(name = "length_score")
    private Integer lengthScore;

    @Column(name = "relevance_score")
    private Integer relevanceScore;

    @Column(name = "readability_score")
    private Integer readabilityScore;

    @Column(name = "violation_score")
    private Integer violationScore;

    @Column(name = "is_violation")
    private Boolean isViolation = false;

    @Column(name = "violation_type", length = 30)
    private String violationType;

    @Column(name = "violation_reason", columnDefinition = "TEXT")
    private String violationReason;

    @Column(name = "violation_words", columnDefinition = "TEXT")
    private String violationWords;

    @Column(name = "spam_score")
    private Integer spamScore;

    @Column(name = "is_spam")
    private Boolean isSpam = false;

    @Column(name = "evaluation_level", length = 10)
    private String evaluationLevel;

    @CreatedDate
    @Column(name = "evaluated_at", nullable = false, updatable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime evaluatedAt;

    public QualityEvaluation() {}

    public String getEvaluationId() {
        return evaluationId;
    }

    public void setEvaluationId(String evaluationId) {
        this.evaluationId = evaluationId;
    }

    public String getCommentId() {
        return commentId;
    }

    public void setCommentId(String commentId) {
        this.commentId = commentId;
    }

    public Integer getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(Integer qualityScore) {
        this.qualityScore = qualityScore;
    }

    public Integer getLengthScore() {
        return lengthScore;
    }

    public void setLengthScore(Integer lengthScore) {
        this.lengthScore = lengthScore;
    }

    public Integer getRelevanceScore() {
        return relevanceScore;
    }

    public void setRelevanceScore(Integer relevanceScore) {
        this.relevanceScore = relevanceScore;
    }

    public Integer getReadabilityScore() {
        return readabilityScore;
    }

    public void setReadabilityScore(Integer readabilityScore) {
        this.readabilityScore = readabilityScore;
    }

    public Integer getViolationScore() {
        return violationScore;
    }

    public void setViolationScore(Integer violationScore) {
        this.violationScore = violationScore;
    }

    public Boolean getViolation() {
        return isViolation;
    }

    public void setViolation(Boolean violation) {
        isViolation = violation;
    }

    public String getViolationType() {
        return violationType;
    }

    public void setViolationType(String violationType) {
        this.violationType = violationType;
    }

    public String getViolationReason() {
        return violationReason;
    }

    public void setViolationReason(String violationReason) {
        this.violationReason = violationReason;
    }

    public String getViolationWords() {
        return violationWords;
    }

    public void setViolationWords(String violationWords) {
        this.violationWords = violationWords;
    }

    public Integer getSpamScore() {
        return spamScore;
    }

    public void setSpamScore(Integer spamScore) {
        this.spamScore = spamScore;
    }

    public Boolean getSpam() {
        return isSpam;
    }

    public void setSpam(Boolean spam) {
        isSpam = spam;
    }

    public String getEvaluationLevel() {
        return evaluationLevel;
    }

    public void setEvaluationLevel(String evaluationLevel) {
        this.evaluationLevel = evaluationLevel;
    }

    public LocalDateTime getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(LocalDateTime evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }
}
