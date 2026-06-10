package com.exam.controller;

import com.exam.common.Result;
import com.exam.service.ScoreAnalysisService;
import com.exam.vo.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/student/scores")
@RequiredArgsConstructor
public class ScoreController {

    private final ScoreAnalysisService scoreAnalysisService;

    @GetMapping("/report/{examRecordId}")
    public Result<ExamReportVO> getExamReport(@PathVariable Long examRecordId) {
        return Result.success(scoreAnalysisService.getExamReport(examRecordId));
    }

    @GetMapping("/radar/{examRecordId}")
    public Result<KnowledgeRadarVO> getKnowledgeRadar(@PathVariable Long examRecordId) {
        return Result.success(scoreAnalysisService.getKnowledgeRadar(examRecordId));
    }

    @GetMapping("/summary")
    public Result<PersonalScoreVO> getPersonalScoreSummary(
            @RequestParam(required = false) Long subjectId,
            HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return Result.success(scoreAnalysisService.getPersonalScoreSummary(userId, subjectId));
    }
}
