package com.exam.controller;

import com.exam.common.Result;
import com.exam.entity.Exam;
import com.exam.entity.ExamAnswer;
import com.exam.entity.ExamRecord;
import com.exam.service.ExamService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/student/exams")
@RequiredArgsConstructor
public class StudentExamController {

    private final ExamService examService;

    @GetMapping("/list")
    public Result<List<Exam>> getStudentExams(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return Result.success(examService.getStudentExams(userId));
    }

    @GetMapping("/{id}")
    public Result<Exam> getExamDetail(@PathVariable Long id) {
        return Result.success(examService.getExamById(id));
    }

    @PostMapping("/{id}/enter")
    public Result<ExamRecord> enterExam(@PathVariable Long id, HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return Result.success(examService.enterExam(id, userId));
    }

    @GetMapping("/records/{recordId}")
    public Result<ExamRecord> getExamRecord(@PathVariable Long recordId) {
        return Result.success(examService.getExamRecord(recordId));
    }

    @GetMapping("/records/{recordId}/answers")
    public Result<List<ExamAnswer>> getExamAnswers(@PathVariable Long recordId) {
        return Result.success(examService.getExamAnswers(recordId));
    }

    @PostMapping("/records/{recordId}/answer")
    public Result<Void> saveAnswer(@PathVariable Long recordId,
                                   @RequestParam Long questionId,
                                   @RequestParam String answer) {
        examService.saveAnswer(recordId, questionId, answer);
        return Result.success();
    }

    @PostMapping("/records/{recordId}/submit")
    public Result<ExamRecord> submitExam(@PathVariable Long recordId,
                                         @RequestParam(defaultValue = "1") Integer submitType,
                                         HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return Result.success(examService.submitExam(recordId, userId, submitType));
    }

    @PostMapping("/records/{recordId}/abnormal")
    public Result<Void> reportAbnormal(@PathVariable Long recordId,
                                       @RequestParam Integer abnormalType,
                                       @RequestParam(required = false) String detail) {
        examService.reportAbnormal(recordId, abnormalType, detail);
        return Result.success();
    }

    @GetMapping("/{examId}/current")
    public Result<ExamRecord> getCurrentExamRecord(@PathVariable Long examId,
                                                    HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("currentUserId");
        return Result.success(examService.getCurrentExamRecord(examId, userId));
    }
}
