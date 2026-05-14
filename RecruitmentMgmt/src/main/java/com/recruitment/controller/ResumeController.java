package com.recruitment.controller;

import com.recruitment.common.dto.ApiResponse;
import com.recruitment.common.enums.ResumeStatus;
import com.recruitment.dto.ResumeScreenRequest;
import com.recruitment.dto.ResumeSubmitRequest;
import com.recruitment.dto.ResumeSubmitResponse;
import com.recruitment.model.Resume;
import com.recruitment.service.ResumeCheckService;
import com.recruitment.service.ResumeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/resumes")
@RequiredArgsConstructor
public class ResumeController {
    private final ResumeService resumeService;
    private final ResumeCheckService resumeCheckService;

    @PostMapping("/check")
    public ResponseEntity<ApiResponse<ResumeScreenRequest.ResumeCheckResult>> checkResumeBeforeSubmit(
            @Valid @RequestBody ResumeSubmitRequest request) {
        log.info("API: 简历投递前检查, positionId: {}", request.getPositionId());
        ResumeScreenRequest.ResumeCheckResult result = resumeService.checkResumeBeforeSubmit(request);
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @PostMapping("/submit")
    public ResponseEntity<ApiResponse<ResumeSubmitResponse>> submitResume(
            @Valid @RequestBody ResumeSubmitRequest request) {
        log.info("API: 接收简历投递请求, positionId: {}", request.getPositionId());
        ResumeSubmitResponse response = resumeService.submitResume(request);
        return ResponseEntity.ok(ApiResponse.success("简历投递成功", response));
    }

    @PostMapping("/screen")
    public ResponseEntity<ApiResponse<Resume>> screenResume(
            @Valid @RequestBody ResumeScreenRequest request) {
        log.info("API: 接收简历筛选请求, resumeId: {}", request.getResumeId());
        Resume resume = resumeService.screenResume(request);
        return ResponseEntity.ok(ApiResponse.success("简历筛选完成", resume));
    }

    @GetMapping("/{resumeId}")
    public ResponseEntity<ApiResponse<Resume>> getResume(@PathVariable String resumeId) {
        Resume resume = resumeService.getResume(resumeId);
        return ResponseEntity.ok(ApiResponse.success(resume));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Resume>>> getAllResumes() {
        List<Resume> resumes = resumeService.getAllResumes();
        return ResponseEntity.ok(ApiResponse.success(resumes));
    }

    @GetMapping("/position/{positionId}")
    public ResponseEntity<ApiResponse<List<Resume>>> getResumesByPosition(
            @PathVariable String positionId) {
        List<Resume> resumes = resumeService.getResumesByPosition(positionId);
        return ResponseEntity.ok(ApiResponse.success(resumes));
    }

    @GetMapping("/candidate/{candidateId}")
    public ResponseEntity<ApiResponse<List<Resume>>> getResumesByCandidate(
            @PathVariable String candidateId) {
        List<Resume> resumes = resumeService.getResumesByCandidate(candidateId);
        return ResponseEntity.ok(ApiResponse.success(resumes));
    }

    @GetMapping("/candidate/{candidateId}/active")
    public ResponseEntity<ApiResponse<List<Resume>>> getCandidateActiveResumes(
            @PathVariable String candidateId) {
        log.info("API: 获取候选人活跃简历, candidateId: {}", candidateId);
        List<Resume> resumes = resumeService.getCandidateActiveResumes(candidateId);
        return ResponseEntity.ok(ApiResponse.success(resumes));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<Resume>>> getResumesByStatus(
            @PathVariable ResumeStatus status) {
        List<Resume> resumes = resumeService.getResumesByStatus(status);
        return ResponseEntity.ok(ApiResponse.success(resumes));
    }

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<Resume>>> getPendingScreenResumes() {
        List<Resume> resumes = resumeService.getPendingScreenResumes();
        return ResponseEntity.ok(ApiResponse.success(resumes));
    }

    @GetMapping("/check-duplicate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkDuplicateSubmission(
            @RequestParam String positionId,
            @RequestParam(required = false) String candidateId,
            @RequestParam(required = false) String candidatePhone) {
        log.info("API: 检查重复投递, positionId: {}", positionId);

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("positionId", positionId);

        if (candidateId != null) {
            ResumeCheckService.ResumeCheckResult checkResult = resumeCheckService.performFullCheck(positionId, candidateId);
            result.put("candidateId", candidateId);
            result.put("canApply", checkResult.isPassed());
            result.put("positionStatusValid", checkResult.isPositionStatusValid());
            result.put("duplicateCheckPassed", checkResult.isDuplicateCheckPassed());
            result.put("availabilityCheckPassed", checkResult.isAvailabilityCheckPassed());
            result.put("errorMessage", checkResult.getErrorMessage());
        } else {
            result.put("error", "请提供候选人ID或手机号");
        }

        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
