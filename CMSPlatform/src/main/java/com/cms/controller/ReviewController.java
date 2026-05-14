package com.cms.controller;

import com.cms.dto.ApiResponse;
import com.cms.dto.ReviewProcessRequest;
import com.cms.entity.ReviewRecord;
import com.cms.service.ReviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    @Autowired
    private ReviewService reviewService;

    @PostMapping("/process")
    public ApiResponse<Map<String, Object>> processReview(@Valid @RequestBody ReviewProcessRequest request) {
        ReviewRecord reviewRecord = reviewService.processReview(request);

        Map<String, Object> result = new HashMap<>();
        result.put("review_id", reviewRecord.getReviewId());
        result.put("content_id", reviewRecord.getContentId());
        result.put("status", reviewRecord.getReviewStatus());
        result.put("review_time", reviewRecord.getReviewTime());

        return ApiResponse.success(result);
    }

    @GetMapping("/{reviewId}")
    public ApiResponse<ReviewRecord> getReview(@PathVariable String reviewId) {
        ReviewRecord reviewRecord = reviewService.getReviewById(reviewId);
        return ApiResponse.success(reviewRecord);
    }

    @GetMapping
    public ApiResponse<List<ReviewRecord>> getAllReviews() {
        List<ReviewRecord> reviews = reviewService.getAllReviews();
        return ApiResponse.success(reviews);
    }

    @GetMapping("/content/{contentId}")
    public ApiResponse<List<ReviewRecord>> getReviewsByContent(@PathVariable String contentId) {
        List<ReviewRecord> reviews = reviewService.getReviewsByContentId(contentId);
        return ApiResponse.success(reviews);
    }

    @GetMapping("/reviewer/{reviewerId}")
    public ApiResponse<List<ReviewRecord>> getReviewsByReviewer(@PathVariable String reviewerId) {
        List<ReviewRecord> reviews = reviewService.getReviewsByReviewerId(reviewerId);
        return ApiResponse.success(reviews);
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<ReviewRecord>> getReviewsByStatus(@PathVariable String status) {
        List<ReviewRecord> reviews = reviewService.getReviewsByStatus(status);
        return ApiResponse.success(reviews);
    }
}
