package com.exam.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.exam.common.Result;
import com.exam.dto.QuestionDTO;
import com.exam.dto.QuestionQueryDTO;
import com.exam.entity.Question;
import com.exam.entity.QuestionVersion;
import com.exam.service.QuestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/teacher/questions")
@RequiredArgsConstructor
public class QuestionController {

    private final QuestionService questionService;

    @GetMapping("/page")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<IPage<Question>> getQuestionPage(QuestionQueryDTO queryDTO) {
        return Result.success(questionService.getQuestionPage(queryDTO));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Question> getQuestionById(@PathVariable Long id) {
        return Result.success(questionService.getQuestionById(id));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Question> createQuestion(@Valid @RequestBody QuestionDTO questionDTO) {
        return Result.success(questionService.createQuestion(questionDTO));
    }

    @PutMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Question> updateQuestion(@Valid @RequestBody QuestionDTO questionDTO) {
        return Result.success(questionService.updateQuestion(questionDTO));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Void> deleteQuestion(@PathVariable Long id) {
        questionService.deleteQuestion(id);
        return Result.success();
    }

    @DeleteMapping("/batch")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Void> batchDeleteQuestions(@RequestBody List<Long> ids) {
        questionService.batchDeleteQuestions(ids);
        return Result.success();
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<List<QuestionVersion>> getQuestionVersions(@PathVariable Long id) {
        return Result.success(questionService.getQuestionVersions(id));
    }

    @GetMapping("/{id}/versions/{version}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Question> getQuestionByVersion(@PathVariable Long id, @PathVariable Integer version) {
        return Result.success(questionService.getQuestionByVersion(id, version));
    }

    @PostMapping("/{id}/rollback/{version}")
    @PreAuthorize("hasAnyRole('ADMIN', 'TEACHER')")
    public Result<Void> rollbackVersion(@PathVariable Long id, @PathVariable Integer version) {
        questionService.rollbackVersion(id, version);
        return Result.success();
    }
}
