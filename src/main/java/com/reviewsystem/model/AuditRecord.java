package com.reviewsystem.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "audit_records")
@EntityListeners(AuditingEntityListener.class)
public class AuditRecord {

    @Id
    @Column(name = "audit_id", length = 50)
    private String auditId;

    @Column(name = "comment_id", nullable = false, length = 50)
    private String commentId;

    @Column(name = "audit_type", length = 20)
    private String auditType = "auto";

    @ElementCollection
    @CollectionTable(name = "audit_rules", joinColumns = @JoinColumn(name = "audit_id"))
    @Column(name = "audit_rule")
    private List<String> auditRules;

    @Column(name = "audit_result", length = 20)
    private String auditResult;

    @Column(name = "audit_reason", columnDefinition = "TEXT")
    private String auditReason;

    @Column(name = "sensitive_words")
    private String sensitiveWords;

    @Column(name = "quality_score")
    private Integer qualityScore;

    @CreatedDate
    @Column(name = "audited_at", nullable = false, updatable = false)
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'")
    private LocalDateTime auditedAt;

    public AuditRecord() {}

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
    }

    public String getCommentId() {
        return commentId;
    }

    public void setCommentId(String commentId) {
        this.commentId = commentId;
    }

    public String getAuditType() {
        return auditType;
    }

    public void setAuditType(String auditType) {
        this.auditType = auditType;
    }

    public List<String> getAuditRules() {
        return auditRules;
    }

    public void setAuditRules(List<String> auditRules) {
        this.auditRules = auditRules;
    }

    public String getAuditResult() {
        return auditResult;
    }

    public void setAuditResult(String auditResult) {
        this.auditResult = auditResult;
    }

    public String getAuditReason() {
        return auditReason;
    }

    public void setAuditReason(String auditReason) {
        this.auditReason = auditReason;
    }

    public String getSensitiveWords() {
        return sensitiveWords;
    }

    public void setSensitiveWords(String sensitiveWords) {
        this.sensitiveWords = sensitiveWords;
    }

    public Integer getQualityScore() {
        return qualityScore;
    }

    public void setQualityScore(Integer qualityScore) {
        this.qualityScore = qualityScore;
    }

    public LocalDateTime getAuditedAt() {
        return auditedAt;
    }

    public void setAuditedAt(LocalDateTime auditedAt) {
        this.auditedAt = auditedAt;
    }
}
