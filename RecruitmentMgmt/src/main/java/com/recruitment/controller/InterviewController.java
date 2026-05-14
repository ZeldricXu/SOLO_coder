package com.recruitment.controller;

import com.recruitment.common.dto.ApiResponse;
import com.recruitment.common.enums.InterviewStatus;
import com.recruitment.dto.InterviewArrangeRequest;
import com.recruitment.dto.InterviewArrangeResponse;
import com.recruitment.dto.InterviewExecuteRequest;
import com.recruitment.model.Interview;
import com.recruitment.service.InterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/interviews")
@RequiredArgsConstructor
public class InterviewController {
    private final InterviewService interviewService;

    @PostMapping("/arrange")
    public ResponseEntity<ApiResponse<InterviewArrangeResponse>> arrangeInterview(
            @Valid @RequestBody InterviewArrangeRequest request) {
        log.info("API: 接收面试安排请求, resumeId: {}", request.getResumeId());
        InterviewArrangeResponse response = interviewService.arrangeInterview(request);
        return ResponseEntity.ok(ApiResponse.success("面试安排成功", response));
    }

    @PostMapping("/execute")
    public ResponseEntity<ApiResponse<Interview>> executeInterview(
            @Valid @RequestBody InterviewExecuteRequest request) {
        log.info("API: 接收面试执行请求, interviewId: {}", request.getInterviewId());
        Interview interview = interviewService.executeInterview(request);
        return ResponseEntity.ok(ApiResponse.success("面试执行完成", interview));
    }

    @GetMapping("/{interviewId}")
    public ResponseEntity<ApiResponse<Interview>> getInterview(@PathVariable String interviewId) {
        Interview interview = interviewService.getInterview(interviewId);
        return ResponseEntity.ok(ApiResponse.success(interview));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Interview>>> getAllInterviews() {
        List<Interview> interviews = interviewService.getAllInterviews();
        return ResponseEntity.ok(ApiResponse.success(interviews));
    }

    @GetMapping("/resume/{resumeId}")
    public ResponseEntity<ApiResponse<List<Interview>>> getInterviewsByResume(
            @PathVariable String resumeId) {
        List<Interview> interviews = interviewService.getInterviewsByResume(resumeId);
        return ResponseEntity.ok(ApiResponse.success(interviews));
    }

    @GetMapping("/interviewer/{interviewerId}")
    public ResponseEntity<ApiResponse<List<Interview>>> getInterviewsByInterviewer(
            @PathVariable String interviewerId) {
        List<Interview> interviews = interviewService.getInterviewsByInterviewer(interviewerId);
        return ResponseEntity.ok(ApiResponse.success(interviews));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<Interview>>> getInterviewsByStatus(
            @PathVariable InterviewStatus status) {
        List<Interview> interviews = interviewService.getInterviewsByStatus(status);
        return ResponseEntity.ok(ApiResponse.success(interviews));
    }

    @GetMapping("/scheduled")
    public ResponseEntity<ApiResponse<List<Interview>>> getScheduledInterviews() {
        List<Interview> interviews = interviewService.getScheduledInterviews();
        return ResponseEntity.ok(ApiResponse.success(interviews));
    }
}
