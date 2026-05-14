
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
@Table(name = "progress_backup")
public class ProgressBackup {

    @Id
    @Column(name = "backup_id", nullable = false, length = 50)
    private String backupId;

    @Column(name = "progress_id", nullable = false, length = 50)
    private String progressId;

    @Column(name = "course_id", nullable = false, length = 50)
    private String courseId;

    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    @Column(name = "progress_status", length = 20)
    private String progressStatus;

    @Column(name = "progress_percent")
    private Integer progressPercent;

    @Column(name = "chapters_completed")
    private Integer chaptersCompleted;

    @Column(name = "total_chapters")
    private Integer totalChapters;

    @Column(name = "learning_time")
    private Long learningTime;

    @Column(name = "backup_reason", length = 50)
    private String backupReason;

    @Column(name = "backup_level", length = 20)
    private String backupLevel;

    @Column(name = "backup_time")
    private LocalDateTime backupTime;

    @Column(name = "is_verified")
    private Boolean isVerified;

    @Column(name = "activity_level")
    private Integer activityLevel;

    @PrePersist
    protected void onCreate() {
        backupTime = LocalDateTime.now();
        if (isVerified == null) {
            isVerified = false;
        }
        if (activityLevel == null) {
            activityLevel = 0;
        }
    }
}
