package com.fooddelivery.controller;

import com.fooddelivery.dto.CreateReviewRequest;
import com.fooddelivery.dto.CreateReviewResponse;
import com.fooddelivery.dto.ApiResponse;
import com.fooddelivery.entity.Review;
import com.fooddelivery.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<CreateReviewResponse>> createReview(@Valid @RequestBody CreateReviewRequest request) {
        CreateReviewResponse response = reviewService.createReview(request);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/{reviewId}")
    public ResponseEntity<ApiResponse<Review>> getReview(@PathVariable String reviewId) {
        Optional<Review> review = reviewService.getReviewById(reviewId);
        if (review.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(review.get()));
        }
        return ResponseEntity.ok(ApiResponse.error(404, "评价不存在"));
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<ApiResponse<Review>> getReviewByOrder(@PathVariable String orderId) {
        Optional<Review> review = reviewService.getReviewByOrderId(orderId);
        if (review.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(review.get()));
        }
        return ResponseEntity.ok(ApiResponse.error(404, "该订单暂无评价"));
    }

    @GetMapping("/restaurant/{restaurantId}")
    public ResponseEntity<ApiResponse<List<Review>>> getReviewsByRestaurant(@PathVariable String restaurantId) {
        List<Review> reviews = reviewService.getReviewsByRestaurantId(restaurantId);
        return ResponseEntity.ok(ApiResponse.success(reviews));
    }

    @GetMapping("/rider/{riderId}")
    public ResponseEntity<ApiResponse<List<Review>>> getReviewsByRider(@PathVariable String riderId) {
        List<Review> reviews = reviewService.getReviewsByRiderId(riderId);
        return ResponseEntity.ok(ApiResponse.success(reviews));
    }
}
