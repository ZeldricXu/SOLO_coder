package com.healthtrack.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "health_advice")
public class HealthAdvice {
    
    @Id
    @Column(name = "advice_id", nullable = false)
    private String adviceId;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(name = "advice_type", nullable = false)
    private String adviceType;
    
    @Column(name = "advice_content", nullable = false, length = 2000)
    private String adviceContent;
    
    @Column(name = "priority", nullable = false)
    private String priority;
    
    @Column(name = "based_indicators", length = 500)
    private String basedIndicators;
    
    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;
    
    @Column(name = "read_status", nullable = false)
    private String readStatus;
    
    @Column(name = "pushed")
    private Boolean pushed;
    
    @Column(name = "pushed_at")
    private LocalDateTime pushedAt;

    public HealthAdvice() {
        this.generatedAt = LocalDateTime.now();
        this.readStatus = "unread";
        this.pushed = false;
    }

    public String getAdviceId() { return adviceId; }
    public void setAdviceId(String adviceId) { this.adviceId = adviceId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getAdviceType() { return adviceType; }
    public void setAdviceType(String adviceType) { this.adviceType = adviceType; }
    public String getAdviceContent() { return adviceContent; }
    public void setAdviceContent(String adviceContent) { this.adviceContent = adviceContent; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getBasedIndicators() { return basedIndicators; }
    public void setBasedIndicators(String basedIndicators) { this.basedIndicators = basedIndicators; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
    public String getReadStatus() { return readStatus; }
    public void setReadStatus(String readStatus) { this.readStatus = readStatus; }
    public Boolean getPushed() { return pushed; }
    public void setPushed(Boolean pushed) { this.pushed = pushed; }
    public LocalDateTime getPushedAt() { return pushedAt; }
    public void setPushedAt(LocalDateTime pushedAt) { this.pushedAt = pushedAt; }
}
