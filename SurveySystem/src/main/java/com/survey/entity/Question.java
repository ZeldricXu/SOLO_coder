package com.survey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "questions")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Question {

    @Id
    @Column(name = "question_id", nullable = false, length = 50)
    private String questionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "survey_id")
    private Survey survey;

    @Column(name = "question_type", nullable = false, length = 30)
    private String questionType;

    @Column(name = "question_content", nullable = false, length = 1000)
    private String questionContent;

    @ElementCollection
    @CollectionTable(name = "question_options", joinColumns = @JoinColumn(name = "question_id"))
    @Column(name = "option_value")
    private List<String> options = new ArrayList<>();

    @Column(name = "required", nullable = false)
    private Boolean required = true;

    @Column(name = "question_order", nullable = false)
    private Integer questionOrder;
}
