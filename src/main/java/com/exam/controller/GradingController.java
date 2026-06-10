package com.exam.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.exam.common.Result;
import com.exam.entity.ExamAnswer;
import com.exam.entity.GradingRecord;
import com.exam.service.GradingService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/grader/grading")
@RequiredArgsConstructor
public class GradingController {

    private final GradingService gradingService;

    @GetMapping("/pending")
    @PreAuthorize("hasAnyRole('ADMIN', 'GRADER')")
    public Result<IPage<ExamAnswer>> getPendingGradingList(
            @RequestParam(required = false) Long examId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            HttpServletRequest request) {
        Long graderId = (Long) request.getAttribute("currentUserId");
        return Result.success(gradingService.getPendingGradingList(graderId, examId, pageNum, pageSize));
    }

    @GetMapping("/records")
    @PreAuthorize("hasAnyRole('ADMIN', 'GRADER')")
    public Result<IPage<GradingRecord>> getGraderGradingList(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            HttpServletRequest request) {
        Long graderId = (Long) request.getAttribute("currentUserId");
        return Result.success(gradingService.getGraderGradingList(graderId, status, pageNum, pageSize));
    }

    @PostMapping("/grade/{answerId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'GRADER')")
    public Result<GradingRecord> gradeQuestion(
            @PathVariable Long answerId,
            @RequestParam BigDecimal score,
            @RequestParam(required = false) String remark,
            HttpServletRequest request) {
        Long graderId = (Long) request.getAttribute("currentUserId");
        return Result.success(gradingService.gradeQuestion(answerId, graderId, score, remark));
    }

    @PostMapping("/arbitration/submit/{answerId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'GRADER')")
    public Result<GradingRecord> submitArbitration(
            @PathVariable Long answerId,
            @RequestParam BigDecimal score,
            @RequestParam(required = false) String remark,
            HttpServletRequest request) {
        Long graderId = (Long) request.getAttribute("currentUserId");
        return Result.success(gradingService.submitArbitration(answerId, graderId, score, remark));
    }

    @PostMapping("/arbitration/handle/{answerId}")
    @PreAuthorize("hasAnyRole('ADMIN')")
    public Result<GradingRecord> handleArbitration(
            @PathVariable Long answerId,
            @RequestParam BigDecimal score,
            @RequestParam(required = false) String remark,
            HttpServletRequest request) {
        Long arbiterId = (Long) request.getAttribute("currentUserId");
        return Result.success(gradingService.handleArbitration(answerId, arbiterId, score, remark));
    }

    @GetMapping("/answer/{answerId}/records")
    @PreAuthorize("hasAnyRole('ADMIN', 'GRADER')")
    public Result<List<GradingRecord>> getGradingRecords(@PathVariable Long answerId) {
        return Result.success(gradingService.getGradingRecords(answerId));
    }

    @PostMapping("/exam/{examId}/assign")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Void> assignSubjectiveQuestions(@PathVariable Long examId) {
        gradingService.assignSubjectiveQuestions(examId);
        return Result.success();
    }
}
