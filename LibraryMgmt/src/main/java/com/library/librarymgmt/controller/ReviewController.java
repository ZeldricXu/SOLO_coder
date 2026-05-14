package com.library.librarymgmt.controller;

import com.library.librarymgmt.dto.ApiResponse;
import com.library.librarymgmt.dto.ReviewRequest;
import com.library.librarymgmt.entity.Review;
import com.library.librarymgmt.service.ReviewService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping
    public ApiResponse<Review> createReview(@Validated @RequestBody ReviewRequest request) {
        return ApiResponse.success(reviewService.createReview(request));
    }

    @GetMapping("/{reviewId}")
    public ApiResponse<Review> getReviewById(@PathVariable String reviewId) {
        Optional<Review> review = reviewService.getReviewById(reviewId);
        if (review.isPresent()) {
            return ApiResponse.success(review.get());
        }
        return ApiResponse.error(404, "评价不存在");
    }

    @GetMapping
    public ApiResponse<List<Review>> getAllReviews() {
        return ApiResponse.success(reviewService.getAllReviews());
    }

    @GetMapping("/book/{bookId}")
    public ApiResponse<List<Review>> getReviewsByBookId(@PathVariable String bookId) {
        return ApiResponse.success(reviewService.getReviewsByBookId(bookId));
    }

    @GetMapping("/reader/{readerId}")
    public ApiResponse<List<Review>> getReviewsByReaderId(@PathVariable String readerId) {
        return ApiResponse.success(reviewService.getReviewsByReaderId(readerId));
    }

    @GetMapping("/book/{bookId}/reader/{readerId}")
    public ApiResponse<List<Review>> getReviewsByBookIdAndReaderId(
            @PathVariable String bookId,
            @PathVariable String readerId) {
        return ApiResponse.success(reviewService.getReviewsByBookIdAndReaderId(bookId, readerId));
    }

    @GetMapping("/book/{bookId}/rating")
    public ApiResponse<Double> getAverageRating(@PathVariable String bookId) {
        return ApiResponse.success(reviewService.getAverageRating(bookId));
    }

    @DeleteMapping("/{reviewId}")
    public ApiResponse<Void> deleteReview(@PathVariable String reviewId) {
        reviewService.deleteReview(reviewId);
        return ApiResponse.success(200, "删除成功", null);
    }
}
