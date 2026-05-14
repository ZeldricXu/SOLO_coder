package com.library.librarymgmt.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.Instant;

@Data
@Entity
@Table(name = "readers")
public class Reader {
    @Id
    @Column(name = "reader_id", length = 50, nullable = false)
    private String readerId;

    @Column(name = "reader_name", length = 100, nullable = false)
    private String readerName;

    @Column(name = "reader_phone", length = 20)
    private String readerPhone;

    @Column(name = "reader_type", length = 20, nullable = false)
    private String readerType;

    @Column(name = "reader_status", length = 20, nullable = false)
    private String readerStatus;

    @Column(name = "borrow_limit", nullable = false)
    private Integer borrowLimit;

    @Column(name = "borrowed_count", nullable = false)
    private Integer borrowedCount;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @PrePersist
    protected void onCreate() {
        if (registeredAt == null) {
            registeredAt = Instant.now();
        }
        if (borrowedCount == null) {
            borrowedCount = 0;
        }
    }
}
