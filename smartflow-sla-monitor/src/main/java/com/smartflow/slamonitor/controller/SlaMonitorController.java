package com.smartflow.slamonitor.controller;

import com.smartflow.common.base.Result;
import com.smartflow.common.dto.SlaInfo;
import com.smartflow.persistence.entity.SlaNotification;
import com.smartflow.persistence.entity.SlaPolicy;
import com.smartflow.persistence.entity.SlaTracking;
import com.smartflow.slamonitor.service.SlaMonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/sla")
@RequiredArgsConstructor
public class SlaMonitorController {

    private final SlaMonitorService slaMonitorService;

    @PostMapping("/policy")
    public Result<SlaPolicy> createPolicy(@RequestBody SlaPolicy policy) {
        SlaPolicy created = slaMonitorService.createPolicy(policy);
        return Result.success(created);
    }

    @GetMapping("/policy/{policyId}")
    public Result<SlaPolicy> getPolicy(@PathVariable Long policyId) {
        SlaPolicy policy = slaMonitorService.getPolicy(policyId);
        return Result.success(policy);
    }

    @GetMapping("/policy/list")
    public Result<List<SlaPolicy>> listPolicies(@RequestParam(required = false) String relatedType) {
        List<SlaPolicy> policies = slaMonitorService.listPolicies(relatedType);
        return Result.success(policies);
    }

    @PostMapping("/tracking/start")
    public Result<SlaTracking> startTracking(
            @RequestParam String policyCode,
            @RequestParam Long relatedId,
            @RequestParam String relatedType,
            @RequestBody(required = false) Map<String, Object> relatedData) {
        SlaTracking tracking = slaMonitorService.startTracking(policyCode, relatedId, relatedType, relatedData);
        return Result.success(tracking);
    }

    @GetMapping("/tracking/{trackingId}/info")
    public Result<SlaInfo> getSlaInfo(@PathVariable Long trackingId) {
        SlaInfo info = slaMonitorService.getSlaInfo(trackingId);
        return Result.success(info);
    }

    @GetMapping("/tracking/related/{relatedId}/{relatedType}")
    public Result<SlaInfo> getSlaInfoByRelated(
            @PathVariable Long relatedId,
            @PathVariable String relatedType) {
        SlaInfo info = slaMonitorService.getSlaInfoByRelated(relatedId, relatedType);
        return Result.success(info);
    }

    @PostMapping("/tracking/{trackingId}/pause")
    public Result<Boolean> pauseTracking(@PathVariable Long trackingId) {
        boolean success = slaMonitorService.pauseTracking(trackingId);
        return Result.success(success);
    }

    @PostMapping("/tracking/{trackingId}/complete")
    public Result<Boolean> completeTracking(@PathVariable Long trackingId) {
        boolean success = slaMonitorService.completeTracking(trackingId);
        return Result.success(success);
    }

    @GetMapping("/notification/list")
    public Result<List<SlaNotification>> getNotifications(
            @RequestParam(required = false) Long trackingId,
            @RequestParam(required = false) Integer status) {
        List<SlaNotification> notifications = slaMonitorService.getNotifications(trackingId, status);
        return Result.success(notifications);
    }

    @PostMapping("/notification/{notificationId}/sent")
    public Result<Boolean> markNotificationSent(@PathVariable Long notificationId) {
        boolean success = slaMonitorService.markNotificationSent(notificationId);
        return Result.success(success);
    }

    @GetMapping("/statistics")
    public Result<Map<String, Object>> getSlaStatistics(
            @RequestParam(required = false) String relatedType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        Map<String, Object> statistics = slaMonitorService.getSlaStatistics(relatedType, startTime, endTime);
        return Result.success(statistics);
    }
}
