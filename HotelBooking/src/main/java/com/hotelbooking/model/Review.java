package com.hotelbooking.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "reviews")
public class Review {
    @Id
    @Column(name = "review_id", length = 50)
    private String reviewId;

    @Column(name = "booking_id", length = 50)
    private String bookingId;

    @Column(name = "hotel_id", length = 50)
    private String hotelId;

    @Column(name = "customer_name", length = 100)
    private String customerName;

    @Column(name = "rating")
    private Integer rating;

    @Column(name = "comment", length = 500)
    private String comment;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public Review() {}

    public String getReviewId() { return reviewId; }
    public void setReviewId(String reviewId) { this.reviewId = reviewId; }
    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }
    public String getHotelId() { return hotelId; }
    public void setHotelId(String hotelId) { this.hotelId = hotelId; }
    public String getCustomerName() { return customerName; }
    public void setCustomerName(String customerName) { this.customerName = customerName; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
