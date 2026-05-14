package com.library.librarymgmt.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.Instant;

@Data
@Entity
@Table(name = "borrows")
public class Borrow {
    @Id
    @Column(name = "borrow_id", length = 50, nullable = false)
    private String borrowId;

    @Column(name = "book_id", length = 50, nullable = false)
    private String bookId;

    @Column(name = "reader_id", length = 50, nullable = false)
    private String readerId;

    @Column(name = "borrow_time", nullable = false)
    private Instant borrowTime;

    @Column(name = "borrow_due", nullable = false)
    private Instant borrowDue;

    @Column(name = "borrow_status", length = 20, nullable = false)
    private String borrowStatus;

    @Column(name = "returned_at")
    private Instant returnedAt;

    @PrePersist
    protected void onCreate() {
        if (borrowTime == null) {
            borrowTime = Instant.now();
        }
    }
}
