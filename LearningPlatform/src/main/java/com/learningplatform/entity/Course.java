
package com.learningplatform.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "courses")
public class Course {

    @Id
    @Column(name = "course_id", nullable = false, length = 50)
    private String courseId;

    @Column(name = "course_name", nullable = false, length = 200)
    private String courseName;

    @Column(name = "course_type", length = 50)
    private String courseType;

    @Column(name = "course_teacher", length = 100)
    private String courseTeacher;

    @Column(name = "course_duration")
    private Integer courseDuration;

    @Column(name = "course_status", length = 20)
    private String courseStatus;

    @Column(name = "course_price", precision = 10, scale = 2)
    private BigDecimal coursePrice;

    @Column(name = "course_description", columnDefinition = "TEXT")
    private String courseDescription;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
