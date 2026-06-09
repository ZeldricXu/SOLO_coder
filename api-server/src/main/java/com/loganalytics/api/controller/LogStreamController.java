package com.loganalytics.api.controller;

import com.loganalytics.api.service.LogStreamService;
import com.loganalytics.common.model.LogEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/stream")
@CrossOrigin(origins = "*")
public class LogStreamController {

    private final LogStreamService logStreamService;

    @Autowired
    public LogStreamController(LogStreamService logStreamService) {
        this.logStreamService = logStreamService;
    }

    @GetMapping(value = "/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String patternId) {
        return logStreamService.createStream(serviceName, level, patternId);
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStreamStats() {
        return ResponseEntity.ok(Map.of(
                "activeConnections", logStreamService.getActiveConnections()
        ));
    }

    @PostMapping("/simulate")
    public ResponseEntity<Map<String, Object>> simulateLog(@RequestBody LogEvent event) {
        logStreamService.simulateLog(event);
        return ResponseEntity.ok(Map.of(
                "status", "sent",
                "eventId", event.getId()
        ));
    }
}
