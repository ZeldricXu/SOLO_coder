package com.meeting.controller;

import com.meeting.config.LockConfig;
import com.meeting.config.ReminderConfig;
import com.meeting.config.InviteConfig;
import com.meeting.config.MeetingTypeConfig;
import com.meeting.dto.ApiResponse;
import com.meeting.service.LockManagerService;
import com.meeting.service.ReminderConfirmService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ConfigController {

    private final LockConfig lockConfig;
    private final ReminderConfig reminderConfig;
    private final InviteConfig inviteConfig;
    private final MeetingTypeConfig meetingTypeConfig;
    private final LockManagerService lockManagerService;
    private final ReminderConfirmService reminderConfirmService;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAllConfigs() {
        Map<String, Object> configs = new HashMap<>();
        configs.put("lock", lockConfig);
        configs.put("reminder", reminderConfig);
        configs.put("invite", inviteConfig);
        configs.put("type", meetingTypeConfig);
        return ResponseEntity.ok(ApiResponse.success(configs));
    }

    @GetMapping("/lock")
    public ResponseEntity<ApiResponse<LockConfig>> getLockConfig() {
        return ResponseEntity.ok(ApiResponse.success(lockConfig));
    }

    @GetMapping("/lock/types")
    public ResponseEntity<ApiResponse<Map<String, LockConfig.LockTypeConfig>>> getLockTypeConfigs() {
        return ResponseEntity.ok(ApiResponse.success(lockManagerService.getAllLockConfigs()));
    }

    @GetMapping("/lock/types/{typeCode}")
    public ResponseEntity<ApiResponse<LockConfig.LockTypeConfig>> getLockConfigByType(@PathVariable String typeCode) {
        LockConfig.LockTypeConfig config = lockManagerService.getLockConfigByType(typeCode);
        return ResponseEntity.ok(ApiResponse.success(config));
    }

    @PostMapping("/lock/types/{typeCode}")
    public ResponseEntity<ApiResponse<LockConfig.LockTypeConfig>> addOrUpdateLockConfig(
            @PathVariable String typeCode,
            @RequestBody LockConfig.LockTypeConfig config) {
        lockManagerService.addOrUpdateLockConfig(typeCode, config);
        return ResponseEntity.ok(ApiResponse.success("锁定配置已更新", config));
    }

    @DeleteMapping("/lock/types/{typeCode}")
    public ResponseEntity<ApiResponse<Void>> removeLockConfig(@PathVariable String typeCode) {
        lockManagerService.removeLockConfig(typeCode);
        return ResponseEntity.ok(ApiResponse.success("锁定配置已删除", null));
    }

    @GetMapping("/reminder")
    public ResponseEntity<ApiResponse<ReminderConfig>> getReminderConfig() {
        return ResponseEntity.ok(ApiResponse.success(reminderConfig));
    }

    @GetMapping("/reminder/strategies")
    public ResponseEntity<ApiResponse<Map<String, ReminderConfig.ReminderStrategyConfig>>> getReminderStrategies() {
        return ResponseEntity.ok(ApiResponse.success(reminderConfirmService.getAllStrategies()));
    }

    @GetMapping("/reminder/strategies/{importance}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getReminderStrategy(@PathVariable String importance) {
        Map<String, Object> strategyInfo = new HashMap<>();
        strategyInfo.put("importance", importance);
        strategyInfo.put("requiredConfirmCount", reminderConfirmService.getRequiredConfirmCount(importance));
        strategyInfo.put("maxReminderCount", reminderConfirmService.getMaxReminderCount(importance));
        strategyInfo.put("reminderIntervalMinutes", reminderConfirmService.getReminderIntervalMinutes(importance));
        return ResponseEntity.ok(ApiResponse.success(strategyInfo));
    }

    @PostMapping("/reminder/strategies/{importance}")
    public ResponseEntity<ApiResponse<ReminderConfig.ReminderStrategyConfig>> addOrUpdateReminderStrategy(
            @PathVariable String importance,
            @RequestBody ReminderConfig.ReminderStrategyConfig config) {
        reminderConfirmService.addOrUpdateStrategy(importance, config);
        return ResponseEntity.ok(ApiResponse.success("提醒策略已更新", config));
    }

    @DeleteMapping("/reminder/strategies/{importance}")
    public ResponseEntity<ApiResponse<Void>> removeReminderStrategy(@PathVariable String importance) {
        reminderConfirmService.removeStrategy(importance);
        return ResponseEntity.ok(ApiResponse.success("提醒策略已删除", null));
    }

    @GetMapping("/invite")
    public ResponseEntity<ApiResponse<InviteConfig>> getInviteConfig() {
        return ResponseEntity.ok(ApiResponse.success(inviteConfig));
    }

    @GetMapping("/type")
    public ResponseEntity<ApiResponse<MeetingTypeConfig>> getMeetingTypeConfig() {
        return ResponseEntity.ok(ApiResponse.success(meetingTypeConfig));
    }
}
