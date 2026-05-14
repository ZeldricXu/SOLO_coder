package com.library.librarymgmt.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.Instant;

@Data
@Entity
@Table(name = "returns")
public class ReturnRecord {
    @Id
    @Column(name = "return_id", length = 50, nullable = false)
    private String returnId;

    @Column(name = "borrow_id", length = 50, nullable = false)
    private String borrowId;

    @Column(name = "book_id", length = 50, nullable = false)
    private String bookId;

    @Column(name = "reader_id", length = 50, nullable = false)
    private String readerId;

    @Column(name = "return_time", nullable = false)
    private Instant returnTime;

    @Column(name = "return_status", length = 20, nullable = false)
    private String returnStatus;

    @Column(name = "overdue_fine")
    private Double overdueFine;

    @PrePersist
    protected void onCreate() {
        if (returnTime == null) {
            returnTime = Instant.now();
        }
    }
}
