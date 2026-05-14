
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
@Table(name = "students")
public class Student {

    @Id
    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    @Column(name = "student_name", nullable = false, length = 100)
    private String studentName;

    @Column(name = "student_phone", length = 20)
    private String studentPhone;

    @Column(name = "student_email", length = 100)
    private String studentEmail;

    @Column(name = "courses_enrolled")
    private Integer coursesEnrolled;

    @Column(name = "courses_completed")
    private Integer coursesCompleted;

    @Column(name = "certificates_earned")
    private Integer certificatesEarned;

    @Column(name = "student_status", length = 20)
    private String studentStatus;

    @Column(name = "registered_at")
    private LocalDateTime registeredAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        registeredAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (coursesEnrolled == null) {
            coursesEnrolled = 0;
        }
        if (coursesCompleted == null) {
            coursesCompleted = 0;
        }
        if (certificatesEarned == null) {
            certificatesEarned = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
