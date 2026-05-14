package com.projectcollab.controller;

import com.projectcollab.dto.AddStageRequest;
import com.projectcollab.dto.ApiResponse;
import com.projectcollab.entity.Stage;
import com.projectcollab.service.stage.StageService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/stages")
public class StageController {

    @Autowired
    private StageService stageService;

    @PostMapping
    public ApiResponse<Stage> addStage(@RequestBody AddStageRequest request) {
        Stage stage = stageService.addStage(request);
        return ApiResponse.success(stage);
    }

    @GetMapping("/project/{projectId}")
    public ApiResponse<List<Stage>> getStagesByProject(@PathVariable String projectId) {
        List<Stage> stages = stageService.getStagesByProjectId(projectId);
        return ApiResponse.success(stages);
    }

    @GetMapping("/project/{projectId}/current")
    public ApiResponse<String> getCurrentStage(@PathVariable String projectId) {
        String currentStage = stageService.getCurrentStage(projectId);
        return ApiResponse.success(currentStage);
    }
}
