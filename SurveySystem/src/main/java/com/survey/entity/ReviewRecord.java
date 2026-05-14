package com.survey.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "review_records")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewRecord {

    @Id
    @Column(name = "review_id", nullable = false, length = 50)
    private String reviewId;

    @Column(name = "answer_id", nullable = false, length = 50)
    private String answerId;

    @Column(name = "review_status", nullable = false, length = 30)
    private String reviewStatus;

    @Column(name = "review_comment", length = 500)
    private String reviewComment;

    @Column(name = "reviewer_id", length = 50)
    private String reviewerId;

    @Column(name = "review_time", nullable = false)
    private LocalDateTime reviewTime;
}
