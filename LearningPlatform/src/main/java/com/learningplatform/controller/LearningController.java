
package com.learningplatform.controller;

import com.learningplatform.dto.ApiResponse;
import com.learningplatform.dto.StartLearningRequest;
import com.learningplatform.dto.StartLearningResponse;
import com.learningplatform.dto.UpdateProgressRequest;
import com.learningplatform.dto.UpdateProgressResponse;
import com.learningplatform.entity.LearningHistory;
import com.learningplatform.entity.Progress;
import com.learningplatform.service.HistoryService;
import com.learningplatform.service.LearningService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/learning")
public class LearningController {

    @Autowired
    private LearningService learningService;

    @Autowired
    private HistoryService historyService;

    @PostMapping("/start")
    public ApiResponse<StartLearningResponse> startLearning(@Validated @RequestBody StartLearningRequest request) {
        StartLearningResponse response = learningService.startLearning(
                request.getCourseId(),
                request.getStudentId()
        );
        return ApiResponse.success(response);
    }

    @PostMapping("/update")
    public ApiResponse<UpdateProgressResponse> updateProgress(@Validated @RequestBody UpdateProgressRequest request) {
        UpdateProgressResponse response = learningService.updateProgress(
                request.getProgressId(),
                request.getChapterId(),
                request.getCompleted(),
                request.getLearningTime()
        );
        return ApiResponse.success(response);
    }

    @GetMapping("/progress/{progressId}")
    public ApiResponse<Progress> getProgress(@PathVariable String progressId) {
        Progress progress = learningService.getProgress(progressId);
        return ApiResponse.success(progress);
    }

    @GetMapping("/student/{studentId}/progresses")
    public ApiResponse<List<Progress>> getStudentProgresses(@PathVariable String studentId) {
        List<Progress> progresses = learningService.getStudentProgresses(studentId);
        return ApiResponse.success(progresses);
    }

    @GetMapping("/course/{courseId}/progresses")
    public ApiResponse<List<Progress>> getCourseProgresses(@PathVariable String courseId) {
        List<Progress> progresses = learningService.getCourseProgresses(courseId);
        return ApiResponse.success(progresses);
    }

    @GetMapping("/student/{studentId}/history")
    public ApiResponse<List<LearningHistory>> getStudentHistory(@PathVariable String studentId) {
        List<LearningHistory> history = historyService.getStudentHistory(studentId);
        return ApiResponse.success(history);
    }

    @GetMapping("/student/{studentId}/course/{courseId}/history")
    public ApiResponse<List<LearningHistory>> getStudentCourseHistory(
            @PathVariable String studentId,
            @PathVariable String courseId) {
        List<LearningHistory> history = historyService.getStudentCourseHistory(studentId, courseId);
        return ApiResponse.success(history);
    }
}
