package com.crm.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "tags")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tag {
    @Id
    @Column(name = "tag_id", nullable = false, unique = true)
    private String tagId;

    @Column(name = "tag_name", nullable = false)
    private String tagName;

    @Column(name = "tag_type")
    private String tagType;

    @Column(name = "tag_status")
    private String tagStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (tagStatus == null) {
            tagStatus = "active";
        }
    }
}
