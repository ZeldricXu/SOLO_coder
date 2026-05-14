
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
@Table(name = "chapter_progress")
public class ChapterProgress {

    @Id
    @Column(name = "chapter_progress_id", nullable = false, length = 50)
    private String chapterProgressId;

    @Column(name = "progress_id", nullable = false, length = 50)
    private String progressId;

    @Column(name = "course_id", nullable = false, length = 50)
    private String courseId;

    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    @Column(name = "chapter_id", nullable = false, length = 50)
    private String chapterId;

    @Column(name = "is_completed")
    private Boolean isCompleted;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "learning_time")
    private Long learningTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (isCompleted == null) {
            isCompleted = false;
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
