package com.loganalytics.api.controller;

import com.loganalytics.alert.AlertService;
import com.loganalytics.common.model.AlertRule;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/alerts")
@CrossOrigin(origins = "*")
public class AlertController {

    private final AlertService alertService;

    @Autowired
    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAlerts(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String service,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int pageSize) {

        return ResponseEntity.ok(alertService.getAlerts(status, severity, service, page, pageSize));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getAlertById(@PathVariable String id) {
        return alertService.getAlertById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeAlert(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {

        String acknowledgedBy = body.get("acknowledgedBy");
        if (acknowledgedBy == null || acknowledgedBy.isBlank()) {
            acknowledgedBy = "unknown";
        }
        return ResponseEntity.ok(alertService.acknowledgeAlert(id, acknowledgedBy));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<Map<String, Object>> resolveAlert(@PathVariable String id) {
        return ResponseEntity.ok(alertService.resolveAlert(id));
    }

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getAlertStats() {
        return ResponseEntity.ok(alertService.getAlertStats());
    }

    @GetMapping("/rules")
    public ResponseEntity<List<AlertRule>> getRules() {
        return ResponseEntity.ok(alertService.getRules());
    }
}
