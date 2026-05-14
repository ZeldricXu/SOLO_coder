
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
@Table(name = "certificates")
public class Certificate {

    @Id
    @Column(name = "certificate_id", nullable = false, length = 50)
    private String certificateId;

    @Column(name = "course_id", nullable = false, length = 50)
    private String courseId;

    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    @Column(name = "certificate_type", length = 20)
    private String certificateType;

    @Column(name = "certificate_number", length = 50)
    private String certificateNumber;

    @Column(name = "certificate_status", length = 20)
    private String certificateStatus;

    @Column(name = "digital_signature", columnDefinition = "TEXT")
    private String digitalSignature;

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @Column(name = "valid_until")
    private LocalDateTime validUntil;

    @PrePersist
    protected void onCreate() {
        issuedAt = LocalDateTime.now();
        if (certificateType == null) {
            certificateType = "completion";
        }
        if (certificateStatus == null) {
            certificateStatus = "valid";
        }
    }
}
