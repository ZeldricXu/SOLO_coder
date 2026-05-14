package com.homeservice.dto;

public class ReviewCreateRequest {
    private String bookingId;
    private Integer reviewRating;
    private String reviewContent;

    public ReviewCreateRequest() {}

    public String getBookingId() { return bookingId; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }
    public Integer getReviewRating() { return reviewRating; }
    public void setReviewRating(Integer reviewRating) { this.reviewRating = reviewRating; }
    public String getReviewContent() { return reviewContent; }
    public void setReviewContent(String reviewContent) { this.reviewContent = reviewContent; }
}
