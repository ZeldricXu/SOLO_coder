
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
@Table(name = "resources")
public class Resource {

    @Id
    @Column(name = "resource_id", nullable = false, length = 50)
    private String resourceId;

    @Column(name = "course_id", nullable = false, length = 50)
    private String courseId;

    @Column(name = "chapter_id", length = 50)
    private String chapterId;

    @Column(name = "resource_name", nullable = false, length = 200)
    private String resourceName;

    @Column(name = "resource_type", length = 50)
    private String resourceType;

    @Column(name = "resource_url", length = 500)
    private String resourceUrl;

    @Column(name = "resource_path", length = 500)
    private String resourcePath;

    @Column(name = "resource_size")
    private Long resourceSize;

    @Column(name = "resource_status", length = 20)
    private String resourceStatus;

    @Column(name = "uploaded_by", length = 100)
    private String uploadedBy;

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
