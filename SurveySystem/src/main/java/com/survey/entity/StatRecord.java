package com.survey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "stat_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StatRecord {

    @Id
    @Column(name = "stat_id", nullable = false, length = 50)
    private String statId;

    @Column(name = "survey_id", nullable = false, length = 50)
    private String surveyId;

    @Column(name = "stat_answer_count", nullable = false)
    private Integer statAnswerCount = 0;

    @Column(name = "stat_reviewed_count", nullable = false)
    private Integer statReviewedCount = 0;

    @Column(name = "stat_completion_rate", nullable = false)
    private Double statCompletionRate = 0.0;

    @Lob
    @Column(name = "stat_question_stat", columnDefinition = "TEXT")
    private String statQuestionStat;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
