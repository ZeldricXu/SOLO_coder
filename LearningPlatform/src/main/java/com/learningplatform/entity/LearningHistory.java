
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
@Table(name = "learning_history")
public class LearningHistory {

    @Id
    @Column(name = "history_id", nullable = false, length = 50)
    private String historyId;

    @Column(name = "student_id", nullable = false, length = 50)
    private String studentId;

    @Column(name = "course_id", length = 50)
    private String courseId;

    @Column(name = "chapter_id", length = 50)
    private String chapterId;

    @Column(name = "history_type", length = 50)
    private String historyType;

    @Column(name = "history_action", length = 100)
    private String historyAction;

    @Column(name = "history_detail", columnDefinition = "TEXT")
    private String historyDetail;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
