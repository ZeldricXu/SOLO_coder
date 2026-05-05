package com.iotconnect.controller;

import com.iotconnect.dto.ApiResponse;
import com.iotconnect.dto.ControlCommandRequest;
import com.iotconnect.dto.ControlCommandResponse;
import com.iotconnect.entity.ControlCommand;
import com.iotconnect.service.DeviceControlService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/devices")
public class DeviceControlController {

    private static final Logger logger = LoggerFactory.getLogger(DeviceControlController.class);

    private final DeviceControlService deviceControlService;

    public DeviceControlController(DeviceControlService deviceControlService) {
        this.deviceControlService = deviceControlService;
    }

    @PostMapping("/control")
    public ResponseEntity<ApiResponse<ControlCommandResponse>> issueCommand(
            @Valid @RequestBody ControlCommandRequest request) {
        
        logger.info("Control command request: deviceId={}, commandType={}",
                request.getDeviceId(), request.getCommandType());
        
        try {
            ControlCommandResponse response = deviceControlService.issueCommand(request);
            return ResponseEntity.ok(ApiResponse.success(response));
        } catch (RuntimeException e) {
            logger.error("Control command failed: {}", e.getMessage());
            
            if (e.getMessage().contains("not found")) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(404, e.getMessage()));
            }
            if (e.getMessage().contains("offline")) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(ApiResponse.error(503, e.getMessage()));
            }
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Control command failed: " + e.getMessage()));
        }
    }

    @GetMapping("/control/{commandId}")
    public ResponseEntity<ApiResponse<ControlCommand>> getCommandStatus(@PathVariable String commandId) {
        logger.debug("Get command status request: commandId={}", commandId);
        
        Optional<ControlCommand> commandOpt = deviceControlService.getCommandStatus(commandId);
        
        if (commandOpt.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(commandOpt.get()));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error(404, "Command not found: " + commandId));
        }
    }

    @GetMapping("/{deviceId}/control/history")
    public ResponseEntity<ApiResponse<List<ControlCommand>>> getDeviceCommandHistory(
            @PathVariable String deviceId) {
        
        logger.debug("Get device command history: deviceId={}", deviceId);
        
        try {
            List<ControlCommand> commands = deviceControlService.getCommandsByDevice(deviceId);
            return ResponseEntity.ok(ApiResponse.success(commands));
        } catch (Exception e) {
            logger.error("Get command history failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to get command history: " + e.getMessage()));
        }
    }

    @GetMapping("/control/pending")
    public ResponseEntity<ApiResponse<List<ControlCommand>>> getPendingCommands() {
        logger.debug("Get pending commands request");
        
        try {
            List<ControlCommand> commands = deviceControlService.getPendingCommands();
            return ResponseEntity.ok(ApiResponse.success(commands));
        } catch (Exception e) {
            logger.error("Get pending commands failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to get pending commands: " + e.getMessage()));
        }
    }

    @GetMapping("/control/pending/count")
    public ResponseEntity<ApiResponse<Long>> getPendingCommandCount() {
        logger.debug("Get pending command count request");
        
        try {
            long count = deviceControlService.getPendingCommandCount();
            return ResponseEntity.ok(ApiResponse.success(count));
        } catch (Exception e) {
            logger.error("Get pending command count failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to get pending command count: " + e.getMessage()));
        }
    }

    @PostMapping("/control/response")
    public ResponseEntity<ApiResponse<Void>> handleCommandResponse(
            @RequestParam String deviceId,
            @RequestParam String commandId,
            @RequestParam String status,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String errorMessage) {
        
        logger.info("Command response received: deviceId={}, commandId={}, status={}",
                deviceId, commandId, status);
        
        try {
            deviceControlService.handleCommandResponse(deviceId, commandId, status, result, errorMessage);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            logger.error("Handle command response failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error("Failed to handle command response: " + e.getMessage()));
        }
    }
}
