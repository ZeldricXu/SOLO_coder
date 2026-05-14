package com.survey.controller;

import com.survey.dto.AnswerSubmitRequest;
import com.survey.dto.AnswerSubmitResponse;
import com.survey.dto.ApiResponse;
import com.survey.entity.AnswerData;
import com.survey.entity.AnswerRecord;
import com.survey.service.AnswerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/answers")
@RequiredArgsConstructor
public class AnswerController {

    private final AnswerService answerService;

    @PostMapping("/submit")
    public ApiResponse<AnswerSubmitResponse> submitAnswer(@Valid @RequestBody AnswerSubmitRequest request) {
        AnswerSubmitResponse response = answerService.submitAnswer(request);
        return ApiResponse.success("答卷提交成功", response);
    }

    @GetMapping("/{answerId}")
    public ApiResponse<AnswerRecord> getAnswer(@PathVariable String answerId) {
        AnswerRecord record = answerService.getAnswer(answerId);
        return ApiResponse.success(record);
    }

    @GetMapping("/{answerId}/details")
    public ApiResponse<List<AnswerData>> getAnswerDetails(@PathVariable String answerId) {
        List<AnswerData> details = answerService.getAnswerDetails(answerId);
        return ApiResponse.success(details);
    }

    @GetMapping("/survey/{surveyId}")
    public ApiResponse<List<AnswerRecord>> getAnswersBySurvey(@PathVariable String surveyId) {
        List<AnswerRecord> records = answerService.getAnswersBySurvey(surveyId);
        return ApiResponse.success(records);
    }

    @GetMapping("/survey/{surveyId}/status/{status}")
    public ApiResponse<List<AnswerRecord>> getAnswersByStatus(
            @PathVariable String surveyId,
            @PathVariable String status) {
        List<AnswerRecord> records = answerService.getAnswersBySurveyAndStatus(surveyId, status);
        return ApiResponse.success(records);
    }

    @GetMapping("/survey/{surveyId}/count")
    public ApiResponse<Long> getAnswerCount(@PathVariable String surveyId) {
        long count = answerService.getAnswerCount(surveyId);
        return ApiResponse.success(count);
    }
}
