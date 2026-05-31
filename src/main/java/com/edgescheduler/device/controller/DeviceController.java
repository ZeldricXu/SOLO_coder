package com.edgescheduler.device.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edgescheduler.common.dto.ApiResponse;
import com.edgescheduler.common.dto.BatchOperationRequest;
import com.edgescheduler.common.dto.BatchOperationResponse;
import com.edgescheduler.device.dto.DeviceActivateRequest;
import com.edgescheduler.device.dto.DeviceDTO;
import com.edgescheduler.device.entity.Device;
import com.edgescheduler.device.service.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @PostMapping
    public Mono<ApiResponse<DeviceDTO>> registerDevice(@Valid @RequestBody DeviceDTO deviceDTO) {
        return Mono.just(ApiResponse.created(deviceService.registerDevice(deviceDTO)));
    }

    @PostMapping("/activate")
    public Mono<ApiResponse<DeviceDTO>> activateDevice(@Valid @RequestBody DeviceActivateRequest request) {
        return Mono.just(ApiResponse.success(deviceService.activateDevice(request)));
    }

    @GetMapping("/{deviceKey}")
    public Mono<ApiResponse<DeviceDTO>> getDevice(@PathVariable String deviceKey) {
        return Mono.just(ApiResponse.success(deviceService.getDeviceByKey(deviceKey)));
    }

    @GetMapping
    public Mono<ApiResponse<IPage<DeviceDTO>>> listDevices(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String productKey,
            @RequestParam(required = false) String status) {
        Page<Device> pageParam = new Page<>(page, size);
        return Mono.just(ApiResponse.success(deviceService.listDevices(pageParam, productKey, status)));
    }

    @PutMapping("/{deviceKey}")
    public Mono<ApiResponse<DeviceDTO>> updateDevice(
            @PathVariable String deviceKey,
            @Valid @RequestBody DeviceDTO deviceDTO) {
        return Mono.just(ApiResponse.success(deviceService.updateDevice(deviceKey, deviceDTO)));
    }

    @DeleteMapping("/{deviceKey}")
    public Mono<ApiResponse<Void>> deleteDevice(@PathVariable String deviceKey) {
        deviceService.deleteDevice(deviceKey);
        return Mono.just(ApiResponse.success(null));
    }

    @GetMapping("/{deviceKey}/status")
    public Mono<ApiResponse<DeviceDTO>> getDeviceStatus(@PathVariable String deviceKey) {
        return Mono.just(ApiResponse.success(deviceService.getDeviceStatus(deviceKey)));
    }

    @PutMapping("/{deviceKey}/status")
    public Mono<ApiResponse<DeviceDTO>> updateDeviceStatus(
            @PathVariable String deviceKey,
            @RequestBody Map<String, String> body) {
        String status = body.get("status");
        return Mono.just(ApiResponse.success(deviceService.updateDeviceStatus(deviceKey, status)));
    }

    @PostMapping("/{deviceKey}/deactivate")
    public Mono<ApiResponse<DeviceDTO>> deactivateDevice(@PathVariable String deviceKey) {
        return Mono.just(ApiResponse.success(deviceService.deactivateDevice(deviceKey)));
    }

    @PostMapping("/heartbeat")
    public Mono<ApiResponse<Void>> heartbeat(@RequestBody Map<String, String> body) {
        String deviceKey = body.get("deviceKey");
        deviceService.heartbeat(deviceKey);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/authenticate")
    public Mono<ApiResponse<Boolean>> authenticate(@RequestBody Map<String, String> body) {
        String deviceKey = body.get("deviceKey");
        String authSecret = body.get("authSecret");
        boolean result = deviceService.authenticateDevice(deviceKey, authSecret);
        return Mono.just(ApiResponse.success(result));
    }

    @PostMapping("/batch")
    public Mono<ApiResponse<BatchOperationResponse>> batchOperation(
            @Valid @RequestBody BatchOperationRequest request) {
        String batchId = "batch_" + UUID.randomUUID().toString().substring(0, 8);
        List<BatchOperationResponse.OperationResult> results = new ArrayList<>();
        int successCount = 0;
        int failedCount = 0;

        for (BatchOperationRequest.BatchOperation op : request.getOperations()) {
            try {
                Object data = executeOperation(op);
                results.add(BatchOperationResponse.OperationResult.builder()
                        .id(op.getId())
                        .action(op.getAction())
                        .success(true)
                        .code(200)
                        .message("success")
                        .data(data)
                        .build());
                successCount++;
            } catch (Exception e) {
                results.add(BatchOperationResponse.OperationResult.builder()
                        .id(op.getId())
                        .action(op.getAction())
                        .success(false)
                        .code(500)
                        .message(e.getMessage())
                        .build());
                failedCount++;
            }
        }

        return Mono.just(ApiResponse.success(BatchOperationResponse.builder()
                .batchId(batchId)
                .results(results)
                .successCount(successCount)
                .failedCount(failedCount)
                .build()));
    }

    private Object executeOperation(BatchOperationRequest.BatchOperation op) {
        return switch (op.getAction()) {
            case "deactivate" -> deviceService.deactivateDevice(op.getId());
            case "delete" -> {
                deviceService.deleteDevice(op.getId());
                yield null;
            }
            case "getStatus" -> deviceService.getDeviceStatus(op.getId());
            default -> throw new IllegalArgumentException("Unsupported action: " + op.getAction());
        };
    }
}
