package com.exam.controller;

import com.exam.common.Result;
import com.exam.service.ScoreAnalysisService;
import com.exam.vo.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/teacher/scores")
@RequiredArgsConstructor
public class TeacherScoreController {

    private final ScoreAnalysisService scoreAnalysisService;

    @GetMapping("/exam/{examId}/statistics")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<ExamStatisticsVO> getExamStatistics(@PathVariable Long examId) {
        return Result.success(scoreAnalysisService.getExamStatistics(examId));
    }

    @GetMapping("/exam/{examId}/distribution")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<List<ScoreDistributionVO>> getScoreDistribution(@PathVariable Long examId) {
        return Result.success(scoreAnalysisService.getScoreDistribution(examId));
    }

    @GetMapping("/report/{examRecordId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<ExamReportVO> getExamReport(@PathVariable Long examRecordId) {
        return Result.success(scoreAnalysisService.getExamReport(examRecordId));
    }

    @GetMapping("/class/{classId}/exam/{examId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<ClassScoreVO> getClassScore(@PathVariable Long classId, @PathVariable Long examId) {
        return Result.success(scoreAnalysisService.getClassScore(classId, examId));
    }
}
