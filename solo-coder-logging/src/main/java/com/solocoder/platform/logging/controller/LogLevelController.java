package com.solocoder.platform.logging.controller;

import com.solocoder.platform.common.model.ApiResponse;
import com.solocoder.platform.logging.model.LogLevelAdjustRequest;
import com.solocoder.platform.logging.model.LogLevelConfig;
import com.solocoder.platform.logging.service.LogLevelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/log-levels")
@RequiredArgsConstructor
public class LogLevelController {

    private final LogLevelService logLevelService;

    @PostMapping
    public ApiResponse<LogLevelConfig> adjustLogLevel(@Valid @RequestBody LogLevelAdjustRequest request) {
        LogLevelConfig config = logLevelService.adjustLogLevel(request);
        return ApiResponse.success(config);
    }

    @GetMapping("/{loggerName}")
    public ApiResponse<LogLevelConfig> getLogLevel(@PathVariable String loggerName) {
        return logLevelService.getLogLevel(loggerName)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "Log level config not found for: " + loggerName));
    }

    @GetMapping
    public ApiResponse<List<LogLevelConfig>> getAllLogLevels() {
        return ApiResponse.success(logLevelService.getAllLogLevels());
    }

    @DeleteMapping("/{loggerName}")
    public ApiResponse<Void> resetLogLevel(@PathVariable String loggerName) {
        logLevelService.resetLogLevel(loggerName);
        return ApiResponse.success();
    }

    @DeleteMapping
    public ApiResponse<Void> resetAllLogLevels() {
        logLevelService.resetAllLogLevels();
        return ApiResponse.success();
    }

    @PostMapping("/recover")
    public ApiResponse<List<LogLevelConfig>> recoverFromPersistence() {
        return ApiResponse.success(logLevelService.recoverFromPersistence());
    }
}
