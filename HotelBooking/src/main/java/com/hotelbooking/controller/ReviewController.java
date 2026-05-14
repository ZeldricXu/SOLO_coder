package com.hotelbooking.controller;

import com.hotelbooking.dto.ApiResponse;
import com.hotelbooking.model.Review;
import com.hotelbooking.service.ReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Review>> submitReview(
            @RequestParam String bookingId,
            @RequestParam String hotelId,
            @RequestParam(required = false) String customerName,
            @RequestParam Integer rating,
            @RequestParam(required = false) String comment) {
        try {
            Review review = reviewService.submitReview(bookingId, hotelId, customerName, rating, comment);
            return ResponseEntity.ok(ApiResponse.success("评价提交成功", review));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping("/hotel/{hotelId}")
    public ResponseEntity<ApiResponse<List<Review>>> getReviewsByHotel(@PathVariable String hotelId) {
        List<Review> reviews = reviewService.getReviewsByHotel(hotelId);
        return ResponseEntity.ok(ApiResponse.success(reviews));
    }

    @GetMapping("/hotel/{hotelId}/rating")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAverageRating(@PathVariable String hotelId) {
        double avgRating = reviewService.getAverageRating(hotelId);
        Map<String, Object> data = new HashMap<>();
        data.put("hotel_id", hotelId);
        data.put("average_rating", avgRating);
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
