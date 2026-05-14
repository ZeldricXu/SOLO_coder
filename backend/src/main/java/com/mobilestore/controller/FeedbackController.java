package com.mobilestore.controller;

import com.mobilestore.common.ApiResponse;
import com.mobilestore.dto.FeedbackProcessRequest;
import com.mobilestore.dto.FeedbackSubmitRequest;
import com.mobilestore.entity.Feedback;
import com.mobilestore.service.FeedbackService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/apps/feedback")
@CrossOrigin(origins = "*")
public class FeedbackController {

    @Autowired
    private FeedbackService feedbackService;

    @PostMapping
    public ApiResponse<Map<String, Object>> submitFeedback(@Valid @RequestBody FeedbackSubmitRequest request) {
        Map<String, Object> result = feedbackService.submitFeedback(request);
        return ApiResponse.success("反馈提交成功，已自动分类", result);
    }

    @PostMapping("/classify-preview")
    public ApiResponse<Map<String, Object>> classifyFeedbackPreview(@Valid @RequestBody FeedbackSubmitRequest request) {
        Map<String, Object> result = feedbackService.classifyFeedbackPreview(request);
        return ApiResponse.success("反馈分类预览成功", result);
    }

    @GetMapping("/classification-rules")
    public ApiResponse<Map<String, List<String>>> getClassificationRules() {
        Map<String, List<String>> rules = feedbackService.getClassificationRules();
        return ApiResponse.success(rules);
    }

    @GetMapping
    public ApiResponse<List<Feedback>> getFeedbacks(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority) {
        List<Feedback> feedbacks = feedbackService.getFeedbacks(appId, status, priority);
        return ApiResponse.success(feedbacks);
    }

    @GetMapping("/{feedbackId}")
    public ApiResponse<Feedback> getFeedback(@PathVariable String feedbackId) {
        Feedback feedback = feedbackService.getFeedback(feedbackId);
        return ApiResponse.success(feedback);
    }

    @PutMapping("/{feedbackId}")
    public ApiResponse<Feedback> processFeedback(
            @PathVariable String feedbackId,
            @RequestBody FeedbackProcessRequest request,
            @RequestParam(required = false) String operator) {
        Feedback feedback = feedbackService.processFeedback(feedbackId, request, operator);
        return ApiResponse.success("反馈处理完成", feedback);
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> getFeedbackStats(@RequestParam String appId) {
        Map<String, Object> stats = feedbackService.getFeedbackStats(appId);
        return ApiResponse.success(stats);
    }
}
