package com.homeservice.dto;

public class ReviewResponse {
    private String reviewId;
    private String status;

    public ReviewResponse() {}

    public ReviewResponse(String reviewId, String status) {
        this.reviewId = reviewId;
        this.status = status;
    }

    public String getReviewId() { return reviewId; }
    public void setReviewId(String reviewId) { this.reviewId = reviewId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
