package com.survey.controller;

import com.survey.dto.ApiResponse;
import com.survey.dto.ReviewRequest;
import com.survey.entity.ReviewRecord;
import com.survey.service.ReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping("/process")
    public ApiResponse<ReviewRecord> processReview(@Valid @RequestBody ReviewRequest request) {
        ReviewRecord record = reviewService.processReview(request);
        return ApiResponse.success("审核处理完成", record);
    }

    @GetMapping("/{reviewId}")
    public ApiResponse<ReviewRecord> getReview(@PathVariable String reviewId) {
        ReviewRecord record = reviewService.getReview(reviewId);
        return ApiResponse.success(record);
    }

    @GetMapping("/answer/{answerId}")
    public ApiResponse<ReviewRecord> getReviewByAnswer(@PathVariable String answerId) {
        return reviewService.getReviewByAnswer(answerId)
                .map(ApiResponse::success)
                .orElseGet(() -> ApiResponse.success(null));
    }

    @GetMapping("/pending")
    public ApiResponse<List<ReviewRecord>> getPendingReviews() {
        List<ReviewRecord> records = reviewService.getPendingReviews();
        return ApiResponse.success(records);
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<ReviewRecord>> getReviewsByStatus(@PathVariable String status) {
        List<ReviewRecord> records = reviewService.getReviewsByStatus(status);
        return ApiResponse.success(records);
    }
}
