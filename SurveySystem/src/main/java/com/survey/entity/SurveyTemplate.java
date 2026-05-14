package com.survey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "survey_templates")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SurveyTemplate {

    @Id
    @Column(name = "template_id", nullable = false, length = 50)
    private String templateId;

    @Column(name = "template_name", nullable = false, length = 200)
    private String templateName;

    @Column(name = "template_type", nullable = false, length = 50)
    private String templateType;

    @Column(name = "template_description", length = 1000)
    private String templateDescription;

    @Lob
    @Column(name = "template_questions", columnDefinition = "TEXT", nullable = false)
    private String templateQuestions;

    @Column(name = "template_status", nullable = false, length = 30)
    private String templateStatus;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
