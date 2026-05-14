package com.example.mailservice.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "archive_record")
public class ArchiveRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "archive_id", unique = true, nullable = false, length = 64)
    private String archiveId;

    @Column(name = "mail_id", nullable = false, length = 64)
    private String mailId;

    @Column(name = "category", nullable = false, length = 64)
    private String category;

    @Column(name = "archive_time", nullable = false)
    private LocalDateTime archiveTime;

    @Column(name = "archive_status", nullable = false, length = 32)
    private String archiveStatus;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
