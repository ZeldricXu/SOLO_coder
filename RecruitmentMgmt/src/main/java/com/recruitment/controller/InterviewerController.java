package com.recruitment.controller;

import com.recruitment.common.dto.ApiResponse;
import com.recruitment.common.enums.InterviewerStatus;
import com.recruitment.common.enums.InterviewType;
import com.recruitment.model.Interviewer;
import com.recruitment.service.InterviewerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/interviewers")
@RequiredArgsConstructor
public class InterviewerController {
    private final InterviewerService interviewerService;

    @PostMapping
    public ResponseEntity<ApiResponse<Interviewer>> createInterviewer(
            @RequestParam String name,
            @RequestParam String department,
            @RequestParam InterviewType type) {
        log.info("API: 创建面试官, name: {}", name);
        Interviewer interviewer = interviewerService.createInterviewer(name, department, type);
        return ResponseEntity.ok(ApiResponse.success("面试官创建成功", interviewer));
    }

    @PutMapping("/{interviewerId}/status")
    public ResponseEntity<ApiResponse<Interviewer>> updateInterviewerStatus(
            @PathVariable String interviewerId,
            @RequestParam InterviewerStatus status) {
        log.info("API: 更新面试官状态, interviewerId: {}, status: {}", interviewerId, status);
        Interviewer interviewer = interviewerService.updateInterviewerStatus(interviewerId, status);
        return ResponseEntity.ok(ApiResponse.success("面试官状态更新成功", interviewer));
    }

    @GetMapping("/{interviewerId}")
    public ResponseEntity<ApiResponse<Interviewer>> getInterviewer(@PathVariable String interviewerId) {
        Interviewer interviewer = interviewerService.getInterviewer(interviewerId);
        return ResponseEntity.ok(ApiResponse.success(interviewer));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Interviewer>>> getAllInterviewers() {
        List<Interviewer> interviewers = interviewerService.getAllInterviewers();
        return ResponseEntity.ok(ApiResponse.success(interviewers));
    }

    @GetMapping("/available")
    public ResponseEntity<ApiResponse<List<Interviewer>>> getAvailableInterviewers() {
        List<Interviewer> interviewers = interviewerService.getAvailableInterviewers();
        return ResponseEntity.ok(ApiResponse.success(interviewers));
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<ApiResponse<List<Interviewer>>> getInterviewersByType(
            @PathVariable InterviewType type) {
        List<Interviewer> interviewers = interviewerService.getInterviewersByType(type);
        return ResponseEntity.ok(ApiResponse.success(interviewers));
    }

    @GetMapping("/available/{type}")
    public ResponseEntity<ApiResponse<List<Interviewer>>> getAvailableInterviewersByType(
            @PathVariable InterviewType type) {
        List<Interviewer> interviewers = interviewerService.getAvailableInterviewersByType(type);
        return ResponseEntity.ok(ApiResponse.success(interviewers));
    }

    @GetMapping("/find-available")
    public ResponseEntity<ApiResponse<Interviewer>> findAvailableInterviewer(
            @RequestParam InterviewType type) {
        Optional<Interviewer> interviewer = interviewerService.findAvailableInterviewer(type);
        return interviewer
                .map(value -> ResponseEntity.ok(ApiResponse.success(value)))
                .orElse(ResponseEntity.ok(ApiResponse.error(404, "没有可用的面试官")));
    }

    @GetMapping("/{interviewerId}/available")
    public ResponseEntity<ApiResponse<Boolean>> isInterviewerAvailable(@PathVariable String interviewerId) {
        boolean available = interviewerService.isInterviewerAvailable(interviewerId);
        return ResponseEntity.ok(ApiResponse.success(available));
    }
}
