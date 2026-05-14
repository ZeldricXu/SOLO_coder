package com.survey.controller;

import com.survey.dto.ApiResponse;
import com.survey.dto.SurveyCreateRequest;
import com.survey.entity.Survey;
import com.survey.service.SurveyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/surveys")
@RequiredArgsConstructor
public class SurveyController {

    private final SurveyService surveyService;

    @PostMapping
    public ApiResponse<Survey> createSurvey(@Valid @RequestBody SurveyCreateRequest request) {
        Survey survey = surveyService.createSurvey(request);
        return ApiResponse.success("问卷创建成功", survey);
    }

    @PutMapping("/{surveyId}")
    public ApiResponse<Survey> updateSurvey(
            @PathVariable String surveyId,
            @Valid @RequestBody SurveyCreateRequest request) {
        Survey survey = surveyService.updateSurvey(surveyId, request);
        return ApiResponse.success("问卷更新成功", survey);
    }

    @DeleteMapping("/{surveyId}")
    public ApiResponse<Void> deleteSurvey(@PathVariable String surveyId) {
        surveyService.deleteSurvey(surveyId);
        return ApiResponse.success("问卷删除成功", null);
    }

    @PostMapping("/{surveyId}/submit")
    public ApiResponse<Survey> submitForReview(@PathVariable String surveyId) {
        Survey survey = surveyService.submitForReview(surveyId);
        return ApiResponse.success("问卷已提交待发布", survey);
    }

    @GetMapping("/{surveyId}")
    public ApiResponse<Survey> getSurvey(@PathVariable String surveyId) {
        Survey survey = surveyService.getSurvey(surveyId);
        return ApiResponse.success(survey);
    }

    @GetMapping
    public ApiResponse<List<Survey>> getAllSurveys() {
        List<Survey> surveys = surveyService.getAllSurveys();
        return ApiResponse.success(surveys);
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<Survey>> getSurveysByStatus(@PathVariable String status) {
        List<Survey> surveys = surveyService.getSurveysByStatus(status);
        return ApiResponse.success(surveys);
    }

    @GetMapping("/type/{type}")
    public ApiResponse<List<Survey>> getSurveysByType(@PathVariable String type) {
        List<Survey> surveys = surveyService.getSurveysByType(type);
        return ApiResponse.success(surveys);
    }
}
