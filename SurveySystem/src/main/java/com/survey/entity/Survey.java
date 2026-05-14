package com.survey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "surveys")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Survey {

    @Id
    @Column(name = "survey_id", nullable = false, length = 50)
    private String surveyId;

    @Column(name = "survey_name", nullable = false, length = 200)
    private String surveyName;

    @Column(name = "survey_type", nullable = false, length = 50)
    private String surveyType;

    @Column(name = "survey_description", length = 1000)
    private String surveyDescription;

    @OneToMany(mappedBy = "survey", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Question> surveyQuestions = new ArrayList<>();

    @Column(name = "survey_status", nullable = false, length = 30)
    private String surveyStatus;

    @Column(name = "survey_deadline")
    private LocalDateTime surveyDeadline;

    @Column(name = "template_id", length = 50)
    private String templateId;

    @Column(name = "need_review", nullable = false)
    private Boolean needReview = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;
}
