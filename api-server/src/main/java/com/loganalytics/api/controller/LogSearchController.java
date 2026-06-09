package com.loganalytics.api.controller;

import com.loganalytics.api.service.LogSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/logs")
@CrossOrigin(origins = "*")
public class LogSearchController {

    private final LogSearchService logSearchService;

    @Autowired
    public LogSearchController(LogSearchService logSearchService) {
        this.logSearchService = logSearchService;
    }

    @GetMapping("/search")
    public ResponseEntity<Map<String, Object>> searchLogs(
            @RequestParam(required = false) String query,
            @RequestParam(required = false, defaultValue = "*") String serviceName,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String patternId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int pageSize) {

        if (startTime == null) startTime = Instant.now().minusSeconds(3600);
        if (endTime == null) endTime = Instant.now();

        return ResponseEntity.ok(logSearchService.searchLogs(
                query, serviceName, level, patternId, startTime, endTime, page, pageSize));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getLogById(@PathVariable String id) {
        return logSearchService.getLogById(id)
                .map(event -> ResponseEntity.ok(Map.of(
                        "id", event.getId(),
                        "timestamp", event.getTimestamp() != null ? event.getTimestamp().toString() : null,
                        "serviceName", event.getServiceName(),
                        "level", event.getLevel() != null ? event.getLevel().name() : null,
                        "message", event.getMessage(),
                        "hostname", event.getHostname(),
                        "traceId", event.getTraceId(),
                        "spanId", event.getSpanId(),
                        "patternId", event.getPatternId(),
                        "fields", event.getFields()
                )))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/context")
    public ResponseEntity<Map<String, Object>> getLogContext(
            @PathVariable String id,
            @RequestParam(defaultValue = "10") int before,
            @RequestParam(defaultValue = "10") int after) {

        return ResponseEntity.ok(logSearchService.getLogContext(id, before, after));
    }
}
