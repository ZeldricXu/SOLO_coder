package com.cms.controller;

import com.cms.dto.ApiResponse;
import com.cms.entity.ReviewReminder;
import com.cms.service.ReviewReminderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/review-reminders")
public class ReviewReminderController {

    @Autowired
    private ReviewReminderService reviewReminderService;

    @PostMapping
    public ResponseEntity<ApiResponse<ReviewReminder>> createReminder(
            @RequestParam String contentId,
            @RequestParam(required = false) String reviewerId,
            @RequestParam(required = false) String reviewerName) {
        try {
            ReviewReminder reminder = reviewReminderService.createReminder(
                contentId, 
                reviewerId != null ? reviewerId : "reviewer_default", 
                reviewerName != null ? reviewerName : "默认审核员");
            return ResponseEntity.ok(ApiResponse.success(reminder));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ReviewReminder>>> getRemindersByReviewerId(
            @RequestParam(required = false) String reviewerId) {
        List<ReviewReminder> reminders;
        if (reviewerId != null) {
            reminders = reviewReminderService.getRemindersByReviewerId(reviewerId);
        } else {
            reminders = reviewReminderService.getPendingRemindersToProcess();
        }
        return ResponseEntity.ok(ApiResponse.success(reminders));
    }

    @GetMapping("/unread")
    public ResponseEntity<ApiResponse<List<ReviewReminder>>> getUnreadReminders(
            @RequestParam String reviewerId) {
        List<ReviewReminder> reminders = reviewReminderService.getUnreadRemindersByReviewerId(reviewerId);
        return ResponseEntity.ok(ApiResponse.success(reminders));
    }

    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Long>> getUnreadCount(@RequestParam String reviewerId) {
        long count = reviewReminderService.countUnreadRemindersByReviewerId(reviewerId);
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    @GetMapping("/content/{contentId}")
    public ResponseEntity<ApiResponse<List<ReviewReminder>>> getRemindersByContentId(
            @PathVariable String contentId) {
        List<ReviewReminder> reminders = reviewReminderService.getRemindersByContentId(contentId);
        return ResponseEntity.ok(ApiResponse.success(reminders));
    }

    @GetMapping("/{reminderId}")
    public ResponseEntity<ApiResponse<ReviewReminder>> getReminderById(@PathVariable String reminderId) {
        try {
            ReviewReminder reminder = reviewReminderService.getReminderById(reminderId);
            return ResponseEntity.ok(ApiResponse.success(reminder));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/{reminderId}/read")
    public ResponseEntity<ApiResponse<Void>> markAsRead(@PathVariable String reminderId) {
        try {
            reviewReminderService.markAsRead(reminderId);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PostMapping("/read-all")
    public ResponseEntity<ApiResponse<Void>> markAllAsRead(@RequestParam String reviewerId) {
        try {
            reviewReminderService.markAllAsReadByReviewer(reviewerId);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @PostMapping("/{reminderId}/resend")
    public ResponseEntity<ApiResponse<ReviewReminder>> resendReminder(@PathVariable String reminderId) {
        try {
            ReviewReminder reminder = reviewReminderService.resendReminder(reminderId);
            return ResponseEntity.ok(ApiResponse.success(reminder));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }

    @DeleteMapping("/content/{contentId}")
    public ResponseEntity<ApiResponse<Void>> cancelRemindersByContentId(@PathVariable String contentId) {
        try {
            reviewReminderService.cancelRemindersByContentId(contentId);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, e.getMessage()));
        }
    }
}
