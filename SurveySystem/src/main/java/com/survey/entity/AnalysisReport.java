package com.survey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "analysis_reports")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisReport {

    @Id
    @Column(name = "report_id", nullable = false, length = 50)
    private String reportId;

    @Column(name = "survey_id", nullable = false, length = 50)
    private String surveyId;

    @Column(name = "report_name", nullable = false, length = 200)
    private String reportName;

    @Lob
    @Column(name = "report_content", columnDefinition = "TEXT", nullable = false)
    private String reportContent;

    @Column(name = "report_status", nullable = false, length = 30)
    private String reportStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
