package com.projmanage.controller;

import com.projmanage.dto.ApiResponse;
import com.projmanage.model.Milestone;
import com.projmanage.service.MilestoneService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/milestones")
public class MilestoneController {

    private final MilestoneService milestoneService;

    public MilestoneController(MilestoneService milestoneService) {
        this.milestoneService = milestoneService;
    }

    @PostMapping
    public ApiResponse<Milestone> createMilestone(@RequestParam String projectId,
                                                   @RequestParam String milestoneName,
                                                   @RequestParam(required = false) LocalDate milestoneDate) {
        Milestone milestone = milestoneService.createMilestone(projectId, milestoneName, milestoneDate);
        return ApiResponse.success(milestone);
    }

    @GetMapping("/{milestoneId}")
    public ApiResponse<Milestone> getMilestoneById(@PathVariable String milestoneId) {
        Optional<Milestone> milestoneOpt = milestoneService.getMilestoneById(milestoneId);
        if (milestoneOpt.isPresent()) {
            return ApiResponse.success(milestoneOpt.get());
        }
        return ApiResponse.error(404, "里程碑不存在");
    }

    @GetMapping("/project/{projectId}")
    public ApiResponse<List<Milestone>> getMilestonesByProject(@PathVariable String projectId) {
        return ApiResponse.success(milestoneService.getMilestonesByProject(projectId));
    }

    @PostMapping("/{milestoneId}/tasks/{taskId}")
    public ApiResponse<Void> assignTaskToMilestone(@PathVariable String milestoneId, @PathVariable String taskId) {
        milestoneService.assignTaskToMilestone(milestoneId, taskId);
        return ApiResponse.success(null);
    }
}
