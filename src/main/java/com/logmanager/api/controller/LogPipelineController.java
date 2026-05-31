package com.logmanager.api.controller;

import com.logmanager.api.dto.LogEntryDTO;
import com.logmanager.api.vo.ApiResponse;
import com.logmanager.common.enums.LogLevel;
import com.logmanager.domain.model.LogEntry;
import com.logmanager.service.LogPipelineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/logs")
@RequiredArgsConstructor
public class LogPipelineController {

    private final LogPipelineService logPipelineService;

    @PostMapping
    public Mono<ApiResponse<LogEntry>> collectLog(@Valid @RequestBody LogEntryDTO dto) {
        LogEntry logEntry = new LogEntry();
        logEntry.setTraceId(dto.getTraceId());
        logEntry.setServiceName(dto.getServiceName());
        logEntry.setLevel(LogLevel.fromString(dto.getLevel()));
        logEntry.setMessage(dto.getMessage());
        logEntry.setLoggerName(dto.getLoggerName());
        logEntry.setThreadName(dto.getThreadName());
        logEntry.setTimestamp(dto.getTimestamp());
        logEntry.setTags(dto.getTags());
        logEntry.setMetadata(dto.getMetadata());

        return logPipelineService.collect(logEntry)
                .map(ApiResponse::created);
    }

    @PostMapping("/batch")
    public Mono<ApiResponse<Flux<LogEntry>>> collectLogs(@RequestBody Flux<LogEntryDTO> dtos) {
        Flux<LogEntry> logEntries = dtos.map(dto -> {
            LogEntry logEntry = new LogEntry();
            logEntry.setTraceId(dto.getTraceId());
            logEntry.setServiceName(dto.getServiceName());
            logEntry.setLevel(LogLevel.fromString(dto.getLevel()));
            logEntry.setMessage(dto.getMessage());
            logEntry.setLoggerName(dto.getLoggerName());
            logEntry.setThreadName(dto.getThreadName());
            logEntry.setTimestamp(dto.getTimestamp());
            logEntry.setTags(dto.getTags());
            logEntry.setMetadata(dto.getMetadata());
            return logEntry;
        });

        return Mono.just(ApiResponse.success(logPipelineService.collectBatch(logEntries)));
    }

    @GetMapping("/service/{serviceName}/level/{level}")
    public Mono<ApiResponse<Flux<LogEntry>>> filterByLevel(@PathVariable String serviceName, @PathVariable String level) {
        return Mono.just(ApiResponse.success(logPipelineService.filterByLevel(serviceName, level)));
    }

    @GetMapping("/trace/{traceId}")
    public Mono<ApiResponse<Flux<LogEntry>>> searchByTraceId(@PathVariable String traceId) {
        return Mono.just(ApiResponse.success(logPipelineService.searchByTraceId(traceId)));
    }

    @GetMapping("/service/{serviceName}/stats")
    public Mono<ApiResponse<Map<String, Long>>> getStats(@PathVariable String serviceName) {
        return logPipelineService.getStats(serviceName)
                .map(ApiResponse::success);
    }
}
