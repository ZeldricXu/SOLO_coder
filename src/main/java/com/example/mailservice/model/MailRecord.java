package com.example.mailservice.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "mail_record")
public class MailRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "mail_id", unique = true, nullable = false, length = 64)
    private String mailId;

    @Column(name = "mail_type", nullable = false, length = 16)
    private String mailType;

    @Column(name = "sender", nullable = false)
    private String sender;

    @Column(name = "recipients", columnDefinition = "TEXT")
    private String recipients;

    @Column(name = "subject", length = 500)
    private String subject;

    @Column(name = "content", columnDefinition = "LONGTEXT")
    private String content;

    @Column(name = "attachments", columnDefinition = "TEXT")
    private String attachments;

    @Column(name = "mail_status", length = 32)
    private String mailStatus;

    @Column(name = "category", length = 64)
    private String category;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    private List<String> recipientList = new ArrayList<>();

    @Transient
    private List<String> attachmentList = new ArrayList<>();

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
