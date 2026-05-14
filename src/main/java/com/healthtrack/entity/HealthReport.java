package com.healthtrack.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "health_report")
public class HealthReport {
    
    @Id
    @Column(name = "report_id", nullable = false)
    private String reportId;
    
    @Column(name = "user_id", nullable = false)
    private String userId;
    
    @Column(name = "report_type", nullable = false)
    private String reportType;
    
    @Column(name = "report_period", nullable = false)
    private String reportPeriod;
    
    @Column(name = "report_data", columnDefinition = "TEXT")
    private String reportData;
    
    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;
    
    @Column(name = "status")
    private String status;

    public HealthReport() {
        this.generatedAt = LocalDateTime.now();
        this.status = "completed";
    }

    public String getReportId() { return reportId; }
    public void setReportId(String reportId) { this.reportId = reportId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getReportType() { return reportType; }
    public void setReportType(String reportType) { this.reportType = reportType; }
    public String getReportPeriod() { return reportPeriod; }
    public void setReportPeriod(String reportPeriod) { this.reportPeriod = reportPeriod; }
    public String getReportData() { return reportData; }
    public void setReportData(String reportData) { this.reportData = reportData; }
    public LocalDateTime getGeneratedAt() { return generatedAt; }
    public void setGeneratedAt(LocalDateTime generatedAt) { this.generatedAt = generatedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
