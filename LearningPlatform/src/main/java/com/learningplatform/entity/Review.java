
package com.learningplatform.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @Column(name = "review_id", nullable = false, length = 50)
    private String reviewId;

    @Column(name = "course_id", nullable = false, length = 50)
    private String courseId;

    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    @Column(name = "review_rating")
    private Integer reviewRating;

    @Column(name = "review_content", columnDefinition = "TEXT")
    private String reviewContent;

    @Column(name = "review_status", length = 20)
    private String reviewStatus;

    @Column(name = "review_time")
    private LocalDateTime reviewTime;

    @PrePersist
    protected void onCreate() {
        reviewTime = LocalDateTime.now();
        if (reviewStatus == null) {
            reviewStatus = "published";
        }
    }
}
