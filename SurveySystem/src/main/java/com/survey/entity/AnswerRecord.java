package com.survey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "answer_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnswerRecord {

    @Id
    @Column(name = "answer_id", nullable = false, length = 50)
    private String answerId;

    @Column(name = "survey_id", nullable = false, length = 50)
    private String surveyId;

    @Column(name = "user_id", length = 50)
    private String userId;

    @OneToMany(mappedBy = "answerRecord", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<AnswerData> answerData = new ArrayList<>();

    @Column(name = "answer_status", nullable = false, length = 30)
    private String answerStatus;

    @Column(name = "answer_time", nullable = false)
    private LocalDateTime answerTime;

    @Column(name = "review_id", length = 50)
    private String reviewId;
}
