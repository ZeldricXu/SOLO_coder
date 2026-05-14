package com.restaurant.mgmt.controller;

import com.restaurant.mgmt.dto.ApiResponse;
import com.restaurant.mgmt.model.Review;
import com.restaurant.mgmt.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    @PostMapping
    public ApiResponse<Review> createReview(@RequestBody Review review) {
        Review saved = reviewService.createReview(review);
        return ApiResponse.success(saved);
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

    @GetMapping("/order/{orderId}")
    public ApiResponse<Review> getReviewByOrderId(@PathVariable String orderId) {
        Optional<Review> review = reviewService.getReviewByOrderId(orderId);
        return review.map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.success(null));
    }

    @GetMapping("/active")
    public ApiResponse<List<Review>> getActiveReviews() {
        List<Review> reviews = reviewService.getActiveReviews();
        return ApiResponse.success(reviews);
    }

    @GetMapping("/good")
    public ApiResponse<List<Review>> getGoodReviews() {
        List<Review> reviews = reviewService.getGoodReviews();
        return ApiResponse.success(reviews);
    }

    @GetMapping("/rating/{rating}")
    public ApiResponse<List<Review>> getReviewsByRating(@PathVariable int rating) {
        List<Review> reviews = reviewService.getReviewsByRating(rating);
        return ApiResponse.success(reviews);
    }

    @GetMapping("/range")
    public ApiResponse<List<Review>> getReviewsByTimeRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        List<Review> reviews = reviewService.getReviewsByTimeRange(startTime, endTime);
        return ApiResponse.success(reviews);
    }

    @PutMapping("/{reviewId}")
    public ApiResponse<Review> updateReview(
            @PathVariable String reviewId,
            @RequestBody Review review) {
        Review updated = reviewService.updateReview(reviewId, review);
        return ApiResponse.success(updated);
    }

    @DeleteMapping("/{reviewId}")
    public ApiResponse<Void> deleteReview(@PathVariable String reviewId) {
        reviewService.deleteReview(reviewId);
        return ApiResponse.success(null);
    }

    @PostMapping("/{reviewId}/reply")
    public ApiResponse<Review> replyReview(
            @PathVariable String reviewId,
            @RequestParam String reply) {
        Review review = reviewService.replyReview(reviewId, reply);
        return ApiResponse.success(review);
    }

    @PostMapping("/{reviewId}/hide")
    public ApiResponse<Review> hideReview(@PathVariable String reviewId) {
        Review review = reviewService.hideReview(reviewId);
        return ApiResponse.success(review);
    }

    @PostMapping("/{reviewId}/show")
    public ApiResponse<Review> showReview(@PathVariable String reviewId) {
        Review review = reviewService.showReview(reviewId);
        return ApiResponse.success(review);
    }

    @GetMapping("/average-rating")
    public ApiResponse<Map<String, Object>> getAverageRating() {
        double avgRating = reviewService.getAverageRating();
        Map<String, Object> result = new HashMap<>();
        result.put("averageRating", avgRating);
        return ApiResponse.success(result);
    }
}
