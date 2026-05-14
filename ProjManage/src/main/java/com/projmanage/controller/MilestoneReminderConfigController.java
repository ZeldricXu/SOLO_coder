package com.projmanage.controller;

import com.projmanage.dto.ApiResponse;
import com.projmanage.model.MilestoneReminderConfig;
import com.projmanage.service.MilestoneReminderConfigService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/milestones/reminder-config")
public class MilestoneReminderConfigController {

    private final MilestoneReminderConfigService reminderConfigService;

    public MilestoneReminderConfigController(MilestoneReminderConfigService reminderConfigService) {
        this.reminderConfigService = reminderConfigService;
    }

    @GetMapping("/milestone/{milestoneId}")
    public ApiResponse<Map<String, Object>> getConfigByMilestoneId(@PathVariable String milestoneId) {
        Optional<MilestoneReminderConfig> configOpt = reminderConfigService.getConfigByMilestoneId(milestoneId);

        Map<String, Object> result = new HashMap<>();
        if (configOpt.isPresent()) {
            MilestoneReminderConfig config = configOpt.get();
            result.put("config", config);
            result.put("description", getConfigDescription(config));
        } else {
            result.put("message", "该里程碑暂无提醒配置，将使用默认配置");
        }

        return ApiResponse.success(result);
    }

    @GetMapping("/project/{projectId}")
    public ApiResponse<Map<String, Object>> getConfigsByProjectId(@PathVariable String projectId) {
        List<MilestoneReminderConfig> configs = reminderConfigService.getConfigsByProjectId(projectId);
        Map<String, Object> result = new HashMap<>();
        result.put("configs", configs);
        result.put("total", configs.size());
        return ApiResponse.success(result);
    }

    @PostMapping("/create-custom")
    public ApiResponse<Map<String, Object>> createCustomConfig(
            @RequestParam String projectId,
            @RequestParam String milestoneId,
            @RequestParam(defaultValue = "3") Integer daysBefore,
            @RequestParam(defaultValue = "true") Boolean enableMultipleReminders,
            @RequestParam(defaultValue = "24") Integer reminderIntervalHours,
            @RequestParam(defaultValue = "3") Integer maxReminderCount) {
        MilestoneReminderConfig config = reminderConfigService.createCustomConfig(
                projectId, milestoneId, daysBefore,
                enableMultipleReminders, reminderIntervalHours, maxReminderCount
        );

        Map<String, Object> result = new HashMap<>();
        result.put("config", config);
        result.put("message", "自定义提醒配置已创建");
        return ApiResponse.success(result);
    }

    @PostMapping("/{configId}/update")
    public ApiResponse<Map<String, Object>> updateConfig(
            @PathVariable String configId,
            @RequestParam(required = false) Integer daysBefore,
            @RequestParam(required = false) Boolean enableMultipleReminders,
            @RequestParam(required = false) Integer reminderIntervalHours,
            @RequestParam(required = false) Integer maxReminderCount) {
        Optional<MilestoneReminderConfig> configOpt = reminderConfigService.getConfigById(configId);
        if (!configOpt.isPresent()) {
            return ApiResponse.error(404, "提醒配置不存在");
        }

        MilestoneReminderConfig config = configOpt.get();
        if (daysBefore != null) {
            config.setDaysBefore(daysBefore);
        }
        if (enableMultipleReminders != null) {
            config.setEnableMultipleReminders(enableMultipleReminders);
        }
        if (reminderIntervalHours != null) {
            config.setReminderIntervalHours(reminderIntervalHours);
        }
        if (maxReminderCount != null) {
            config.setMaxReminderCount(maxReminderCount);
        }

        MilestoneReminderConfig updatedConfig = reminderConfigService.saveConfig(config);
        Map<String, Object> result = new HashMap<>();
        result.put("config", updatedConfig);
        result.put("message", "提醒配置已更新");
        return ApiResponse.success(result);
    }

    @PostMapping("/{configId}/reset")
    public ApiResponse<Map<String, Object>> resetConfig(@PathVariable String configId) {
        Optional<MilestoneReminderConfig> configOpt = reminderConfigService.getConfigById(configId);
        if (!configOpt.isPresent()) {
            return ApiResponse.error(404, "提醒配置不存在");
        }

        MilestoneReminderConfig config = configOpt.get();
        reminderConfigService.resetToDefault(config);

        Map<String, Object> result = new HashMap<>();
        result.put("config", config);
        result.put("message", "已恢复默认提醒配置");
        return ApiResponse.success(result);
    }

    @DeleteMapping("/{configId}")
    public ApiResponse<Map<String, Object>> deleteConfig(@PathVariable String configId) {
        Optional<MilestoneReminderConfig> configOpt = reminderConfigService.getConfigById(configId);
        if (!configOpt.isPresent()) {
            return ApiResponse.error(404, "提醒配置不存在");
        }

        reminderConfigService.deleteConfig(configId);
        Map<String, Object> result = new HashMap<>();
        result.put("message", "提醒配置已删除");
        return ApiResponse.success(result);
    }

    @GetMapping("/defaults")
    public ApiResponse<Map<String, Object>> getDefaultConfigInfo() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("days_before", 3);
        defaults.put("enable_multiple_reminders", true);
        defaults.put("reminder_interval_hours", 24);
        defaults.put("max_reminder_count", 3);
        defaults.put("description", "默认配置：提前3天开始提醒，每天提醒一次，最多提醒3次");

        Map<String, Object> result = new HashMap<>();
        result.put("default_config", defaults);
        result.put("available_presets", new String[]{
                "提前1天提醒",
                "提前3天提醒",
                "提前1周提醒",
                "提前2周提醒",
                "到期当天提醒"
        });
        return ApiResponse.success(result);
    }

    @PostMapping("/preset")
    public ApiResponse<Map<String, Object>> applyPreset(
            @RequestParam String projectId,
            @RequestParam String milestoneId,
            @RequestParam String presetType) {
        Integer daysBefore;
        Boolean enableMultipleReminders = true;
        Integer reminderIntervalHours = 24;
        Integer maxReminderCount = 3;

        switch (presetType.toLowerCase()) {
            case "1-day":
                daysBefore = 1;
                maxReminderCount = 1;
                break;
            case "3-day":
                daysBefore = 3;
                break;
            case "1-week":
                daysBefore = 7;
                reminderIntervalHours = 48;
                break;
            case "2-week":
                daysBefore = 14;
                reminderIntervalHours = 72;
                break;
            case "day-of":
                daysBefore = 0;
                enableMultipleReminders = false;
                break;
            default:
                return ApiResponse.error(400, "未知的预设类型，可用预设：1-day, 3-day, 1-week, 2-week, day-of");
        }

        MilestoneReminderConfig config = reminderConfigService.createCustomConfig(
                projectId, milestoneId, daysBefore,
                enableMultipleReminders, reminderIntervalHours, maxReminderCount
        );

        Map<String, Object> result = new HashMap<>();
        result.put("config", config);
        result.put("preset", presetType);
        result.put("message", "已应用预设提醒配置");
        return ApiResponse.success(result);
    }

    private String getConfigDescription(MilestoneReminderConfig config) {
        StringBuilder sb = new StringBuilder();
        sb.append("提前").append(config.getDaysBefore()).append("天开始提醒");

        if (Boolean.TRUE.equals(config.getEnableMultipleReminders())) {
            sb.append("，每").append(config.getReminderIntervalHours()).append("小时提醒一次");
            sb.append("，最多提醒").append(config.getMaxReminderCount()).append("次");
        } else {
            sb.append("，仅提醒一次");
        }

        if (config.getReminderCount() != null && config.getReminderCount() > 0) {
            sb.append("（已提醒").append(config.getReminderCount()).append("次）");
        }

        return sb.toString();
    }
}
