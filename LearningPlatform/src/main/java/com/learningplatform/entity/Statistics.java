
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
@Table(name = "statistics")
public class Statistics {

    @Id
    @Column(name = "stat_id", nullable = false, length = 50)
    private String statId;

    @Column(name = "stat_month", length = 7)
    private String statMonth;

    @Column(name = "course_count")
    private Integer courseCount;

    @Column(name = "student_count")
    private Integer studentCount;

    @Column(name = "enrollment_count")
    private Integer enrollmentCount;

    @Column(name = "completion_count")
    private Integer completionCount;

    @Column(name = "certificate_count")
    private Integer certificateCount;

    @Column(name = "review_count")
    private Integer reviewCount;

    @Column(name = "average_rating", precision = 3, scale = 2)
    private java.math.BigDecimal averageRating;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        updatedAt = LocalDateTime.now();
        if (courseCount == null) {
            courseCount = 0;
        }
        if (studentCount == null) {
            studentCount = 0;
        }
        if (enrollmentCount == null) {
            enrollmentCount = 0;
        }
        if (completionCount == null) {
            completionCount = 0;
        }
        if (certificateCount == null) {
            certificateCount = 0;
        }
        if (reviewCount == null) {
            reviewCount = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
