package com.taskplatform.controller;

import com.taskplatform.common.response.ApiResponse;
import com.taskplatform.config.ConfigService;
import com.taskplatform.persistence.entity.ConfigEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ConfigController {

    private final ConfigService configService;

    @GetMapping("/{namespace}/{key}")
    public ApiResponse<String> getConfig(
            @PathVariable String namespace,
            @PathVariable String key,
            @RequestParam(required = false) String defaultValue) {
        String value = configService.getString(namespace, key, defaultValue);
        return ApiResponse.success(value);
    }

    @PostMapping("/{namespace}/{key}")
    public ApiResponse<ConfigEntry> setConfig(
            @PathVariable String namespace,
            @PathVariable String key,
            @RequestBody Map<String, Object> request) {
        Object value = request.get("value");
        String stringValue = value instanceof Map ?
                com.taskplatform.common.util.JsonUtil.toJson(value) : value.toString();
        String appliedBy = (String) request.getOrDefault("appliedBy", "system");

        ConfigEntry entry = configService.setConfig(namespace, key, stringValue, appliedBy);
        return ApiResponse.created(entry);
    }

    @GetMapping("/{namespace}/{key}/int")
    public ApiResponse<Integer> getIntConfig(
            @PathVariable String namespace,
            @PathVariable String key,
            @RequestParam(defaultValue = "0") int defaultValue) {
        return ApiResponse.success(configService.getInt(namespace, key, defaultValue));
    }

    @GetMapping("/{namespace}/{key}/boolean")
    public ApiResponse<Boolean> getBooleanConfig(
            @PathVariable String namespace,
            @PathVariable String key,
            @RequestParam(defaultValue = "false") boolean defaultValue) {
        return ApiResponse.success(configService.getBoolean(namespace, key, defaultValue));
    }
}
