package com.recruitment.controller;

import com.recruitment.common.dto.ApiResponse;
import com.recruitment.common.enums.PositionStatus;
import com.recruitment.model.Position;
import com.recruitment.service.PositionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/positions")
@RequiredArgsConstructor
public class PositionController {
    private final PositionService positionService;

    @PostMapping
    public ResponseEntity<ApiResponse<Position>> createPosition(
            @RequestParam String name,
            @RequestParam String type,
            @RequestParam String department,
            @RequestParam Integer count,
            @RequestParam(required = false) String salary,
            @RequestParam(required = false) String requirement) {
        log.info("API: 创建职位, name: {}, type: {}", name, type);
        Position position = positionService.createPosition(name, type, department, count, salary, requirement);
        return ResponseEntity.ok(ApiResponse.success("职位创建成功", position));
    }

    @PostMapping("/{positionId}/publish")
    public ResponseEntity<ApiResponse<Position>> publishPosition(@PathVariable String positionId) {
        log.info("API: 发布职位, positionId: {}", positionId);
        Position position = positionService.publishPosition(positionId);
        return ResponseEntity.ok(ApiResponse.success("职位发布成功", position));
    }

    @PostMapping("/{positionId}/close")
    public ResponseEntity<ApiResponse<Position>> closePosition(@PathVariable String positionId) {
        log.info("API: 关闭职位, positionId: {}", positionId);
        Position position = positionService.closePosition(positionId);
        return ResponseEntity.ok(ApiResponse.success("职位关闭成功", position));
    }

    @PostMapping("/{positionId}/suspend")
    public ResponseEntity<ApiResponse<Position>> suspendPosition(@PathVariable String positionId) {
        log.info("API: 暂停职位招聘, positionId: {}", positionId);
        Position position = positionService.suspendPosition(positionId);
        return ResponseEntity.ok(ApiResponse.success("职位暂停招聘成功", position));
    }

    @PostMapping("/{positionId}/resume")
    public ResponseEntity<ApiResponse<Position>> resumePosition(@PathVariable String positionId) {
        log.info("API: 恢复职位招聘, positionId: {}", positionId);
        Position position = positionService.resumePosition(positionId);
        return ResponseEntity.ok(ApiResponse.success("职位恢复招聘成功", position));
    }

    @PutMapping("/{positionId}/status")
    public ResponseEntity<ApiResponse<Position>> updatePositionStatus(
            @PathVariable String positionId,
            @RequestParam PositionStatus status) {
        log.info("API: 更新职位状态, positionId: {}, status: {}", positionId, status);
        Position position = positionService.updatePositionStatus(positionId, status);
        return ResponseEntity.ok(ApiResponse.success("职位状态更新成功", position));
    }

    @PutMapping("/{positionId}")
    public ResponseEntity<ApiResponse<Position>> updatePosition(
            @PathVariable String positionId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) Integer count,
            @RequestParam(required = false) String salary,
            @RequestParam(required = false) String requirement) {
        log.info("API: 更新职位, positionId: {}", positionId);
        Position position = positionService.updatePosition(positionId, name, type, department, count, salary, requirement);
        return ResponseEntity.ok(ApiResponse.success("职位更新成功", position));
    }

    @GetMapping("/{positionId}")
    public ResponseEntity<ApiResponse<Position>> getPosition(@PathVariable String positionId) {
        Position position = positionService.getPosition(positionId);
        return ResponseEntity.ok(ApiResponse.success(position));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<Position>>> getAllPositions() {
        List<Position> positions = positionService.getAllPositions();
        return ResponseEntity.ok(ApiResponse.success(positions));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<Position>>> getPositionsByStatus(
            @PathVariable PositionStatus status) {
        List<Position> positions = positionService.getPositionsByStatus(status);
        return ResponseEntity.ok(ApiResponse.success(positions));
    }

    @GetMapping("/department/{department}")
    public ResponseEntity<ApiResponse<List<Position>>> getPositionsByDepartment(
            @PathVariable String department) {
        List<Position> positions = positionService.getPositionsByDepartment(department);
        return ResponseEntity.ok(ApiResponse.success(positions));
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<ApiResponse<List<Position>>> getPositionsByType(
            @PathVariable String type) {
        List<Position> positions = positionService.getPositionsByType(type);
        return ResponseEntity.ok(ApiResponse.success(positions));
    }

    @GetMapping("/{positionId}/recruiting")
    public ResponseEntity<ApiResponse<Boolean>> isPositionRecruiting(@PathVariable String positionId) {
        boolean recruiting = positionService.isPositionRecruiting(positionId);
        return ResponseEntity.ok(ApiResponse.success(recruiting));
    }

    @GetMapping("/{positionId}/available")
    public ResponseEntity<ApiResponse<Map<String, Object>>> checkPositionAvailability(@PathVariable String positionId) {
        Position position = positionService.getPosition(positionId);
        Map<String, Object> result = new java.util.HashMap<>();
        result.put("positionId", positionId);
        result.put("status", position.getPositionStatus());
        result.put("isRecruiting", position.getPositionStatus() == PositionStatus.RECRUITING);
        result.put("positionCount", position.getPositionCount());
        result.put("resumeCount", position.getResumeCount());
        result.put("hasAvailableSlots", positionService.hasAvailableSlots(positionId));
        result.put("positionType", position.getPositionType());
        result.put("positionTypeName", positionService.getPositionTypeName(position.getPositionType()));
        return ResponseEntity.ok(ApiResponse.success(result));
    }
}
