
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
@Table(name = "chapters")
public class Chapter {

    @Id
    @Column(name = "chapter_id", nullable = false, length = 50)
    private String chapterId;

    @Column(name = "course_id", nullable = false, length = 50)
    private String courseId;

    @Column(name = "chapter_name", nullable = false, length = 200)
    private String chapterName;

    @Column(name = "chapter_order")
    private Integer chapterOrder;

    @Column(name = "chapter_duration")
    private Integer chapterDuration;

    @Column(name = "chapter_status", length = 20)
    private String chapterStatus;

    @Column(name = "chapter_description", columnDefinition = "TEXT")
    private String chapterDescription;

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
