package com.fitnesscenter.controller;

import com.fitnesscenter.dto.ApiResponse;
import com.fitnesscenter.dto.TrainingRequest;
import com.fitnesscenter.dto.TrainingResponse;
import com.fitnesscenter.model.Training;
import com.fitnesscenter.service.TrainingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/trainings")
public class TrainingController {

    private final TrainingService trainingService;

    public TrainingController(TrainingService trainingService) {
        this.trainingService = trainingService;
    }

    @PostMapping("/record")
    public ApiResponse<TrainingResponse> recordTraining(@RequestBody TrainingRequest request) {
        TrainingResponse response = trainingService.recordTraining(request);
        return ApiResponse.success(response);
    }

    @GetMapping("/{trainingId}")
    public ApiResponse<Training> getTrainingById(@PathVariable String trainingId) {
        Training training = trainingService.getTrainingById(trainingId);
        return ApiResponse.success(training);
    }

    @GetMapping("/member/{memberId}")
    public ApiResponse<List<Training>> getTrainingsByMemberId(@PathVariable String memberId) {
        List<Training> trainings = trainingService.getTrainingsByMemberId(memberId);
        return ApiResponse.success(trainings);
    }

    @GetMapping
    public ApiResponse<List<Training>> getAllTrainings() {
        List<Training> trainings = trainingService.getAllTrainings();
        return ApiResponse.success(trainings);
    }
}
