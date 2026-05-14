package com.library.librarymgmt.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.Instant;

@Data
@Entity
@Table(name = "reserves")
public class Reserve {
    @Id
    @Column(name = "reserve_id", length = 50, nullable = false)
    private String reserveId;

    @Column(name = "book_id", length = 50, nullable = false)
    private String bookId;

    @Column(name = "reader_id", length = 50, nullable = false)
    private String readerId;

    @Column(name = "reserve_time", nullable = false)
    private Instant reserveTime;

    @Column(name = "reserve_status", length = 20, nullable = false)
    private String reserveStatus;

    @Column(name = "notified", nullable = false)
    private Boolean notified;

    @PrePersist
    protected void onCreate() {
        if (reserveTime == null) {
            reserveTime = Instant.now();
        }
        if (notified == null) {
            notified = false;
        }
    }
}
