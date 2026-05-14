package com.library.librarymgmt.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.Instant;

@Data
@Entity
@Table(name = "books")
public class Book {
    @Id
    @Column(name = "book_id", length = 50, nullable = false)
    private String bookId;

    @Column(name = "book_name", length = 200, nullable = false)
    private String bookName;

    @Column(name = "book_author", length = 100, nullable = false)
    private String bookAuthor;

    @Column(name = "book_category", length = 50, nullable = false)
    private String bookCategory;

    @Column(name = "book_publisher", length = 100)
    private String bookPublisher;

    @Column(name = "book_status", length = 20, nullable = false)
    private String bookStatus;

    @Column(name = "book_stock", nullable = false)
    private Integer bookStock;

    @Column(name = "book_available", nullable = false)
    private Integer bookAvailable;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @PrePersist
    protected void onCreate() {
        if (registeredAt == null) {
            registeredAt = Instant.now();
        }
    }
}
