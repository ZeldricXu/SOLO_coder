package com.fitnesscenter.controller;

import com.fitnesscenter.dto.ApiResponse;
import com.fitnesscenter.model.Coach;
import com.fitnesscenter.service.CoachService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/coaches")
public class CoachController {

    private final CoachService coachService;

    public CoachController(CoachService coachService) {
        this.coachService = coachService;
    }

    @PostMapping("/create")
    public ApiResponse<Coach> createCoach(@RequestBody Coach coach) {
        Coach savedCoach = coachService.createCoach(coach);
        return ApiResponse.success(savedCoach);
    }

    @GetMapping("/{coachId}")
    public ApiResponse<Coach> getCoachById(@PathVariable String coachId) {
        Coach coach = coachService.getCoachById(coachId);
        return ApiResponse.success(coach);
    }

    @GetMapping
    public ApiResponse<List<Coach>> getAllCoaches() {
        List<Coach> coaches = coachService.getAllCoaches();
        return ApiResponse.success(coaches);
    }

    @GetMapping("/available")
    public ApiResponse<List<Coach>> getAvailableCoaches() {
        List<Coach> coaches = coachService.getAvailableCoaches();
        return ApiResponse.success(coaches);
    }

    @GetMapping("/type/{coachType}")
    public ApiResponse<List<Coach>> getCoachesByType(@PathVariable String coachType) {
        List<Coach> coaches = coachService.getCoachesByType(coachType);
        return ApiResponse.success(coaches);
    }

    @PutMapping("/{coachId}/status")
    public ApiResponse<Coach> updateCoachStatus(@PathVariable String coachId, @RequestParam String status) {
        Coach coach = coachService.updateCoachStatus(coachId, status);
        return ApiResponse.success(coach);
    }
}
