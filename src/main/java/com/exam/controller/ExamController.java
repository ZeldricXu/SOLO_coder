package com.exam.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.exam.common.Result;
import com.exam.entity.Exam;
import com.exam.service.ExamService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/teacher/exams")
@RequiredArgsConstructor
public class ExamController {

    private final ExamService examService;

    @GetMapping("/page")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<IPage<Exam>> getExamPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String keyword) {
        return Result.success(examService.getExamPage(pageNum, pageSize, subjectId, status, keyword));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Exam> getExamById(@PathVariable Long id) {
        return Result.success(examService.getExamById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Exam> createExam(@RequestBody Exam exam) {
        return Result.success(examService.createExam(exam));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Exam> updateExam(@RequestBody Exam exam) {
        return Result.success(examService.updateExam(exam));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Void> deleteExam(@PathVariable Long id) {
        examService.deleteExam(id);
        return Result.success();
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Void> publishExam(@PathVariable Long id) {
        examService.publishExam(id);
        return Result.success();
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Void> startExam(@PathVariable Long id) {
        examService.startExam(id);
        return Result.success();
    }

    @PostMapping("/{id}/end")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Void> endExam(@PathVariable Long id) {
        examService.endExam(id);
        return Result.success();
    }
}
