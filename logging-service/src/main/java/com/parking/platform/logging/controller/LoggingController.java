package com.parking.platform.logging.controller;

import com.parking.platform.common.dto.ApiResponse;
import com.parking.platform.logging.entity.LogEntry;
import com.parking.platform.logging.service.StructuredLogger;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/logs")
public class LoggingController {

    private final StructuredLogger structuredLogger;

    public LoggingController(StructuredLogger structuredLogger) {
        this.structuredLogger = structuredLogger;
    }

    @PostMapping
    public ApiResponse<LogEntry> createLog(@RequestBody LogEntry entry) {
        structuredLogger.log(
                entry.getLevel(),
                entry.getService(),
                entry.getMessage(),
                entry.getTraceId(),
                entry.getRequestId(),
                entry.getUserId(),
                entry.getContext()
        );
        return ApiResponse.created(entry);
    }

    @GetMapping
    public ApiResponse<List<LogEntry>> queryLogs(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "100") Integer limit) {
        return ApiResponse.success(structuredLogger.queryLogs(service, level, from, to, limit));
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Long>> getStats() {
        return ApiResponse.success(structuredLogger.getStatistics());
    }
}
