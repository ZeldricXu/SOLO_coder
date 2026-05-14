package com.library.librarymgmt.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.Instant;

@Data
@Entity
@Table(name = "history_logs")
public class HistoryLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "log_type", length = 50, nullable = false)
    private String logType;

    @Column(name = "ref_id", length = 50, nullable = false)
    private String refId;

    @Column(name = "book_id", length = 50)
    private String bookId;

    @Column(name = "reader_id", length = 50)
    private String readerId;

    @Column(name = "action", length = 200, nullable = false)
    private String action;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
