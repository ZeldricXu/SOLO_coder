package com.healthtrack.controller;

import com.healthtrack.dto.ApiResponse;
import com.healthtrack.entity.HealthGoal;
import com.healthtrack.service.GoalManagementService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/goals")
public class GoalController {

    @Autowired
    private GoalManagementService goalManagementService;

    @PostMapping
    public ResponseEntity<ApiResponse<HealthGoal>> createGoal(@RequestBody HealthGoal goal) {
        try {
            HealthGoal created = goalManagementService.createGoal(goal);
            return ResponseEntity.ok(ApiResponse.success(created));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "创建目标失败: " + e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<HealthGoal>>> getUserGoals(@RequestParam String userId) {
        try {
            List<HealthGoal> goals = goalManagementService.getUserGoals(userId);
            return ResponseEntity.ok(ApiResponse.success(goals));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "查询目标失败: " + e.getMessage()));
        }
    }

    @GetMapping("/{goalId}")
    public ResponseEntity<ApiResponse<HealthGoal>> getGoalById(@PathVariable String goalId) {
        try {
            return goalManagementService.getGoalById(goalId)
                    .map(goal -> ResponseEntity.ok(ApiResponse.success(goal)))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "查询目标失败: " + e.getMessage()));
        }
    }

    @PutMapping("/{goalId}")
    public ResponseEntity<ApiResponse<HealthGoal>> updateGoal(@PathVariable String goalId, @RequestBody HealthGoal goal) {
        try {
            HealthGoal updated = goalManagementService.updateGoal(goalId, goal);
            return ResponseEntity.ok(ApiResponse.success(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "更新目标失败: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{goalId}")
    public ResponseEntity<ApiResponse<Void>> deleteGoal(@PathVariable String goalId) {
        try {
            goalManagementService.deleteGoal(goalId);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "删除目标失败: " + e.getMessage()));
        }
    }
}
