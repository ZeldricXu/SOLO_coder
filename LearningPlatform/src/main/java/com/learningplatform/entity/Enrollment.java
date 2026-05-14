
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
@Table(name = "enrollments")
public class Enrollment {

    @Id
    @Column(name = "enrollment_id", nullable = false, length = 50)
    private String enrollmentId;

    @Column(name = "course_id", nullable = false, length = 50)
    private String courseId;

    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    @Column(name = "enrollment_status", length = 20)
    private String enrollmentStatus;

    @Column(name = "enrolled_at")
    private LocalDateTime enrolledAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        enrolledAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (enrollmentStatus == null) {
            enrollmentStatus = "active";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
