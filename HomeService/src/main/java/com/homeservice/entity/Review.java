package com.homeservice.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "reviews")
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "review_id", unique = true, nullable = false)
    private String reviewId;

    @Column(name = "booking_id", nullable = false)
    private String bookingId;

    @Column(name = "customer_id", nullable = false)
    private String customerId;

    @Column(name = "staff_id", nullable = false)
    private String staffId;

    @Column(name = "review_rating", nullable = false)
    private Integer reviewRating;

    @Column(name = "review_content", length = 2000)
    private String reviewContent;

    @Column(name = "review_time")
    private Instant reviewTime;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
        updatedAt = Instant.now();
        if (reviewTime == null) {
            reviewTime = Instant.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    public Review() {}

    public Review(String reviewId, String bookingId, String customerId, String staffId,
                  Integer reviewRating, String reviewContent) {
        this.reviewId = reviewId;
        this.bookingId = bookingId;
        this.customerId = customerId;
        this.staffId = staffId;
        this.reviewRating = reviewRating;
        this.reviewContent = reviewContent;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getReviewId() { return reviewId; }
    public void setReviewId(String reviewId) { this.reviewId = reviewId; }
    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getStaffId() { return staffId; }
    public void setStaffId(String staffId) { this.staffId = staffId; }
    public Integer getReviewRating() { return reviewRating; }
    public void setReviewRating(Integer reviewRating) { this.reviewRating = reviewRating; }
    public String getReviewContent() { return reviewContent; }
    public void setReviewContent(String reviewContent) { this.reviewContent = reviewContent; }
    public Instant getReviewTime() { return reviewTime; }
    public void setReviewTime(Instant reviewTime) { this.reviewTime = reviewTime; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
