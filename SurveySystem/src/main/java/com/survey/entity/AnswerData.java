package com.survey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "answer_data")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AnswerData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "answer_id")
    private AnswerRecord answerRecord;

    @Column(name = "question_id", nullable = false, length = 50)
    private String questionId;

    @Column(name = "answer_value", length = 2000)
    private String answerValue;
}
