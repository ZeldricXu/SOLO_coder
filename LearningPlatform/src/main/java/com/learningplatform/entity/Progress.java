
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
@Table(name = "progress")
public class Progress {

    @Id
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

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        startedAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (progressStatus == null) {
            progressStatus = "in_progress";
        }
        if (progressPercent == null) {
            progressPercent = 0;
        }
        if (chaptersCompleted == null) {
            chaptersCompleted = 0;
        }
        if (learningTime == null) {
            learningTime = 0L;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
