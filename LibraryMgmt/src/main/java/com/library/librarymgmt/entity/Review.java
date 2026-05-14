package com.library.librarymgmt.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.Instant;

@Data
@Entity
@Table(name = "reviews")
public class Review {
    @Id
    @Column(name = "review_id", length = 50, nullable = false)
    private String reviewId;

    @Column(name = "book_id", length = 50, nullable = false)
    private String bookId;

    @Column(name = "reader_id", length = 50, nullable = false)
    private String readerId;

    @Column(name = "review_rating", nullable = false)
    private Integer reviewRating;

    @Column(name = "review_content", length = 1000)
    private String reviewContent;

    @Column(name = "review_time", nullable = false)
    private Instant reviewTime;

    @PrePersist
    protected void onCreate() {
        if (reviewTime == null) {
            reviewTime = Instant.now();
        }
    }
}
