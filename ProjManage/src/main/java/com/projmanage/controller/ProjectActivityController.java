package com.projmanage.controller;

import com.projmanage.config.Constants;
import com.projmanage.dto.ApiResponse;
import com.projmanage.model.ProjectActivity;
import com.projmanage.service.ProjectActivityService;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/projects/activity")
public class ProjectActivityController {

    private final ProjectActivityService projectActivityService;

    public ProjectActivityController(ProjectActivityService projectActivityService) {
        this.projectActivityService = projectActivityService;
    }

    @GetMapping("/{projectId}")
    public ApiResponse<Map<String, Object>> getProjectActivity(@PathVariable String projectId) {
        Optional<ProjectActivity> activityOpt = projectActivityService.getProjectActivity(projectId);

        Map<String, Object> result = new HashMap<>();
        if (activityOpt.isPresent()) {
            ProjectActivity activity = activityOpt.get();
            result.put("activity", activity);
            result.put("activity_level_description", getActivityLevelDescription(activity.getActivityLevel()));
            result.put("stat_frequency_description", getStatFrequencyDescription(activity.getStatFrequencyMinutes()));
        } else {
            result.put("activity_level", Constants.ACTIVITY_LEVEL_LOW);
            result.put("stat_frequency_minutes", Constants.STAT_FREQUENCY_LOW_MINUTES);
            result.put("activity_level_description", "低活跃度");
            result.put("stat_frequency_description", "每60分钟统计一次");
        }

        return ApiResponse.success(result);
    }

    @GetMapping("/{projectId}/level")
    public ApiResponse<Map<String, Object>> getProjectActivityLevel(@PathVariable String projectId) {
        String level = projectActivityService.getProjectActivityLevel(projectId);
        Map<String, Object> result = new HashMap<>();
        result.put("activity_level", level);
        result.put("description", getActivityLevelDescription(level));
        return ApiResponse.success(result);
    }

    @GetMapping("/{projectId}/frequency")
    public ApiResponse<Map<String, Object>> getProjectStatFrequency(@PathVariable String projectId) {
        int frequency = projectActivityService.getProjectActivity(projectId)
                .map(pa -> pa.getStatFrequencyMinutes() != null ? pa.getStatFrequencyMinutes() : Constants.STAT_FREQUENCY_LOW_MINUTES)
                .orElse(Constants.STAT_FREQUENCY_LOW_MINUTES);

        Map<String, Object> result = new HashMap<>();
        result.put("stat_frequency_minutes", frequency);
        result.put("description", getStatFrequencyDescription(frequency));
        return ApiResponse.success(result);
    }

    @PostMapping("/{projectId}/custom-frequency")
    public ApiResponse<Map<String, Object>> setCustomStatFrequency(
            @PathVariable String projectId,
            @RequestParam Integer frequencyMinutes) {
        if (frequencyMinutes < 1 || frequencyMinutes > 1440) {
            return ApiResponse.error(400, "统计频率必须在1-1440分钟之间");
        }

        ProjectActivity activity = projectActivityService.setCustomStatFrequency(projectId, frequencyMinutes);
        Map<String, Object> result = new HashMap<>();
        result.put("activity", activity);
        result.put("message", "自定义统计频率已设置");
        return ApiResponse.success(result);
    }

    @PostMapping("/{projectId}/reset-frequency")
    public ApiResponse<Map<String, Object>> resetStatFrequency(@PathVariable String projectId) {
        ProjectActivity activity = projectActivityService.resetToDefaultStatFrequency(projectId);
        Map<String, Object> result = new HashMap<>();
        result.put("activity", activity);
        result.put("message", "已恢复默认统计频率（根据活跃度自动调整）");
        return ApiResponse.success(result);
    }

    @GetMapping("/levels")
    public ApiResponse<Map<String, Object>> getActivityLevelsInfo() {
        Map<String, Object> levels = new HashMap<>();
        levels.put(Constants.ACTIVITY_LEVEL_HIGH, Map.of(
                "description", "高活跃度",
                "stat_frequency_minutes", Constants.STAT_FREQUENCY_HIGH_MINUTES,
                "frequency_description", "每5分钟统计一次",
                "update_count_threshold", ">=" + Constants.HIGH_ACTIVITY_THRESHOLD
        ));
        levels.put(Constants.ACTIVITY_LEVEL_MEDIUM, Map.of(
                "description", "中活跃度",
                "stat_frequency_minutes", Constants.STAT_FREQUENCY_MEDIUM_MINUTES,
                "frequency_description", "每15分钟统计一次",
                "update_count_threshold", ">=" + Constants.MEDIUM_ACTIVITY_THRESHOLD
        ));
        levels.put(Constants.ACTIVITY_LEVEL_LOW, Map.of(
                "description", "低活跃度",
                "stat_frequency_minutes", Constants.STAT_FREQUENCY_LOW_MINUTES,
                "frequency_description", "每60分钟统计一次",
                "update_count_threshold", "<" + Constants.MEDIUM_ACTIVITY_THRESHOLD
        ));
        levels.put(Constants.ACTIVITY_LEVEL_INACTIVE, Map.of(
                "description", "不活跃",
                "stat_frequency_minutes", Constants.STAT_FREQUENCY_INACTIVE_MINUTES,
                "frequency_description", "每天统计一次",
                "days_threshold", "超过" + Constants.INACTIVE_DAYS_THRESHOLD + "天无更新"
        ));

        Map<String, Object> result = new HashMap<>();
        result.put("activity_levels", levels);
        return ApiResponse.success(result);
    }

    private String getActivityLevelDescription(String level) {
        if (level == null) return "未知";
        switch (level) {
            case Constants.ACTIVITY_LEVEL_HIGH:
                return "高活跃度（任务频繁更新）";
            case Constants.ACTIVITY_LEVEL_MEDIUM:
                return "中活跃度（任务更新正常）";
            case Constants.ACTIVITY_LEVEL_LOW:
                return "低活跃度（任务更新较少）";
            case Constants.ACTIVITY_LEVEL_INACTIVE:
                return "不活跃（长时间无更新）";
            default:
                return "未知";
        }
    }

    private String getStatFrequencyDescription(Integer minutes) {
        if (minutes == null) return "默认频率";
        if (minutes <= 5) return "高频统计（" + minutes + "分钟）";
        if (minutes <= 15) return "中高频统计（" + minutes + "分钟）";
        if (minutes <= 60) return "低频统计（" + minutes + "分钟）";
        return "超低频统计（" + minutes + "分钟）";
    }
}
