package com.iotplatform.device.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iotplatform.common.dto.PageQuery;
import com.iotplatform.common.dto.PageResult;
import com.iotplatform.common.dto.Result;
import com.iotplatform.device.dto.DeviceAuthDTO;
import com.iotplatform.device.dto.DeviceHeartbeatDTO;
import com.iotplatform.device.dto.DeviceRegisterDTO;
import com.iotplatform.device.entity.SysDevice;
import com.iotplatform.device.service.DeviceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/devices")
@RequiredArgsConstructor
public class DeviceController {

    private final DeviceService deviceService;

    @PostMapping("/register")
    public Mono<Result<SysDevice>> registerDevice(@Valid @RequestBody DeviceRegisterDTO dto) {
        return deviceService.registerDevice(dto)
                .map(Result::success);
    }

    @GetMapping("/{deviceId}")
    public Mono<Result<SysDevice>> getDevice(@PathVariable String deviceId) {
        return deviceService.getDevice(deviceId)
                .map(Result::success);
    }

    @GetMapping
    public Mono<Result<PageResult<SysDevice>>> listDevices(
            @RequestParam(required = false) String deviceType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @ModelAttribute PageQuery pageQuery) {
        return deviceService.listDevices(deviceType, status, keyword,
                        pageQuery.getPageNum(), pageQuery.getPageSize())
                .map(page -> {
                    PageResult<SysDevice> pageResult = new PageResult<>(
                            page.getRecords(),
                            page.getTotal(),
                            page.getPages(),
                            page.getCurrent(),
                            page.getSize()
                    );
                    return Result.success(pageResult);
                });
    }

    @PostMapping("/auth")
    public Mono<Result<Boolean>> authenticateDevice(@Valid @RequestBody DeviceAuthDTO dto) {
        return deviceService.authenticateDevice(dto)
                .map(Result::success);
    }

    @PostMapping("/heartbeat")
    public Mono<Result<Void>> heartbeat(@Valid @RequestBody DeviceHeartbeatDTO dto) {
        return deviceService.heartbeat(dto)
                .then(Mono.just(Result.success(null)));
    }

    @PostMapping("/{deviceId}/activate")
    public Mono<Result<SysDevice>> activateDevice(@PathVariable String deviceId) {
        return deviceService.activateDevice(deviceId)
                .map(Result::success);
    }

    @PostMapping("/{deviceId}/deactivate")
    public Mono<Result<Void>> deactivateDevice(@PathVariable String deviceId) {
        return deviceService.deactivateDevice(deviceId)
                .then(Mono.just(Result.success(null)));
    }

    @DeleteMapping("/{deviceId}")
    public Mono<Result<Void>> deleteDevice(@PathVariable String deviceId) {
        return deviceService.deleteDevice(deviceId)
                .then(Mono.just(Result.success(null)));
    }

    @GetMapping("/stats")
    public Mono<Result<Map<String, Long>>> getDeviceStats() {
        return deviceService.getDeviceStats()
                .map(Result::success);
    }

    @PutMapping("/{deviceId}/metadata")
    public Mono<Result<SysDevice>> updateMetadata(@PathVariable String deviceId,
                                                   @RequestBody Map<String, Object> metadata) {
        return deviceService.updateDeviceMetadata(deviceId, metadata)
                .map(Result::success);
    }
}
