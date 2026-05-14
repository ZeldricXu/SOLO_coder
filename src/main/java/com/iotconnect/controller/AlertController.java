package com.iotconnect.controller;

import com.iotconnect.dto.ApiResponse;
import com.iotconnect.entity.AlertEvent;
import com.iotconnect.entity.AlertRule;
import com.iotconnect.service.AlertConfigService;
import com.iotconnect.service.AlertEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/alerts")
public class AlertController {

    private static final Logger logger = LoggerFactory.getLogger(AlertController.class);

    private final AlertConfigService alertConfigService;
    private final AlertEngineService alertEngineService;

    public AlertController(AlertConfigService alertConfigService, 
                            AlertEngineService alertEngineService) {
        this.alertConfigService = alertConfigService;
        this.alertEngineService = alertEngineService;
    }

    @PostMapping("/rules")
    public ResponseEntity<ApiResponse<AlertRule>> createAlertRule(@RequestBody AlertRule rule) {
        logger.info("Create alert rule request: ruleName={}", rule.getRuleName());
        
        try {
            AlertRule createdRule = alertConfigService.createAlertRule(rule);
            return ResponseEntity.ok(ApiResponse.success(createdRule));
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid alert rule: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(ApiResponse.error(400, "Invalid rule: " + e.getMessage()));
        } catch (Exception e) {
            logger.error("Create alert rule failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Create rule failed: " + e.getMessage()));
        }
    }

    @GetMapping("/rules")
    public ResponseEntity<ApiResponse<List<AlertRule>>> getAllAlertRules(
            @RequestParam(required = false) Boolean enabled) {
        
        logger.debug("Get all alert rules request: enabled={}", enabled);
        
        try {
            List<AlertRule> rules;
            if (Boolean.TRUE.equals(enabled)) {
                rules = alertConfigService.getEnabledAlertRules();
            } else {
                rules = alertConfigService.getAllAlertRules();
            }
            return ResponseEntity.ok(ApiResponse.success(rules));
        } catch (Exception e) {
            logger.error("Get alert rules failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Get rules failed: " + e.getMessage()));
        }
    }

    @GetMapping("/rules/{ruleId}")
    public ResponseEntity<ApiResponse<AlertRule>> getAlertRule(@PathVariable String ruleId) {
        logger.debug("Get alert rule: ruleId={}", ruleId);
        
        Optional<AlertRule> ruleOpt = alertConfigService.getAlertRule(ruleId);
        
        if (ruleOpt.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(ruleOpt.get()));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "Rule not found: " + ruleId));
        }
    }

    @PutMapping("/rules/{ruleId}")
    public ResponseEntity<ApiResponse<AlertRule>> updateAlertRule(
            @PathVariable String ruleId,
            @RequestBody AlertRule rule) {
        
        logger.info("Update alert rule request: ruleId={}", ruleId);
        
        try {
            AlertRule updatedRule = alertConfigService.updateAlertRule(ruleId, rule);
            return ResponseEntity.ok(ApiResponse.success(updatedRule));
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(404, e.getMessage()));
            }
            logger.error("Update alert rule failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Update rule failed: " + e.getMessage()));
        }
    }

    @DeleteMapping("/rules/{ruleId}")
    public ResponseEntity<ApiResponse<Void>> deleteAlertRule(@PathVariable String ruleId) {
        logger.info("Delete alert rule request: ruleId={}", ruleId);
        
        try {
            alertConfigService.deleteAlertRule(ruleId);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(404, e.getMessage()));
            }
            logger.error("Delete alert rule failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Delete rule failed: " + e.getMessage()));
        }
    }

    @PostMapping("/rules/{ruleId}/enable")
    public ResponseEntity<ApiResponse<AlertRule>> enableRule(@PathVariable String ruleId) {
        logger.info("Enable alert rule: ruleId={}", ruleId);
        
        try {
            AlertRule rule = alertConfigService.enableRule(ruleId);
            return ResponseEntity.ok(ApiResponse.success(rule));
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(404, e.getMessage()));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Enable rule failed: " + e.getMessage()));
        }
    }

    @PostMapping("/rules/{ruleId}/disable")
    public ResponseEntity<ApiResponse<AlertRule>> disableRule(@PathVariable String ruleId) {
        logger.info("Disable alert rule: ruleId={}", ruleId);
        
        try {
            AlertRule rule = alertConfigService.disableRule(ruleId);
            return ResponseEntity.ok(ApiResponse.success(rule));
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(404, e.getMessage()));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Disable rule failed: " + e.getMessage()));
        }
    }

    @GetMapping("/rules/device-type/{deviceType}")
    public ResponseEntity<ApiResponse<List<AlertRule>>> getRulesByDeviceType(
            @PathVariable String deviceType) {
        
        logger.debug("Get rules by device type: deviceType={}", deviceType);
        
        try {
            List<AlertRule> rules = alertConfigService.getRulesByDeviceType(deviceType);
            return ResponseEntity.ok(ApiResponse.success(rules));
        } catch (Exception e) {
            logger.error("Get rules by device type failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Get rules failed: " + e.getMessage()));
        }
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<AlertEvent>>> getActiveAlerts() {
        logger.debug("Get active alerts request");
        
        try {
            List<AlertEvent> alerts = alertEngineService.getActiveAlerts();
            return ResponseEntity.ok(ApiResponse.success(alerts));
        } catch (Exception e) {
            logger.error("Get active alerts failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Get active alerts failed: " + e.getMessage()));
        }
    }

    @GetMapping("/device/{deviceId}")
    public ResponseEntity<ApiResponse<List<AlertEvent>>> getAlertsByDevice(
            @PathVariable String deviceId) {
        
        logger.debug("Get alerts by device: deviceId={}", deviceId);
        
        try {
            List<AlertEvent> alerts = alertEngineService.getAlertsByDevice(deviceId);
            return ResponseEntity.ok(ApiResponse.success(alerts));
        } catch (Exception e) {
            logger.error("Get alerts by device failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Get alerts failed: " + e.getMessage()));
        }
    }

    @GetMapping("/{alertId}")
    public ResponseEntity<ApiResponse<AlertEvent>> getAlert(@PathVariable String alertId) {
        logger.debug("Get alert: alertId={}", alertId);
        
        Optional<AlertEvent> alertOpt = alertEngineService.getAlert(alertId);
        
        if (alertOpt.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(alertOpt.get()));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "Alert not found: " + alertId));
        }
    }

    @PostMapping("/{alertId}/acknowledge")
    public ResponseEntity<ApiResponse<AlertEvent>> acknowledgeAlert(@PathVariable String alertId) {
        logger.info("Acknowledge alert: alertId={}", alertId);
        
        try {
            AlertEvent alert = alertEngineService.acknowledgeAlert(alertId);
            return ResponseEntity.ok(ApiResponse.success(alert));
        } catch (RuntimeException e) {
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(404, e.getMessage()));
            }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Acknowledge alert failed: " + e.getMessage()));
        }
    }
}
