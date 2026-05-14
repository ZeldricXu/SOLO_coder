package com.homeservice.controller;

import com.homeservice.dto.ApiResponse;
import com.homeservice.dto.ReviewCreateRequest;
import com.homeservice.dto.ReviewResponse;
import com.homeservice.entity.Review;
import com.homeservice.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    @PostMapping("/create")
    public ApiResponse<ReviewResponse> createReview(@RequestBody ReviewCreateRequest request) {
        ReviewResponse response = reviewService.createReview(request);
        return ApiResponse.success(response);
    }

    @GetMapping
    public ApiResponse<List<Review>> getAllReviews() {
        List<Review> reviews = reviewService.getAllReviews();
        return ApiResponse.success(reviews);
    }

    @GetMapping("/{reviewId}")
    public ApiResponse<Review> getReview(@PathVariable String reviewId) {
        Review review = reviewService.getReviewById(reviewId);
        return ApiResponse.success(review);
    }

    @GetMapping("/booking/{bookingId}")
    public ApiResponse<Review> getReviewByBooking(@PathVariable String bookingId) {
        Review review = reviewService.getReviewByBookingId(bookingId);
        return ApiResponse.success(review);
    }

    @GetMapping("/staff/{staffId}")
    public ApiResponse<List<Review>> getReviewsByStaff(@PathVariable String staffId) {
        List<Review> reviews = reviewService.getReviewsByStaffId(staffId);
        return ApiResponse.success(reviews);
    }

    @GetMapping("/customer/{customerId}")
    public ApiResponse<List<Review>> getReviewsByCustomer(@PathVariable String customerId) {
        List<Review> reviews = reviewService.getReviewsByCustomerId(customerId);
        return ApiResponse.success(reviews);
    }

    @GetMapping("/staff/{staffId}/rating")
    public ApiResponse<Double> getStaffAverageRating(@PathVariable String staffId) {
        Double rating = reviewService.getStaffAverageRating(staffId);
        return ApiResponse.success(rating);
    }
}
