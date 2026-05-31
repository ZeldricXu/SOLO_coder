package com.edgescheduler.protocol.controller;

import com.edgescheduler.common.dto.ApiResponse;
import com.edgescheduler.protocol.dto.ProtocolAdapterDTO;
import com.edgescheduler.protocol.dto.ProtocolDriverDTO;
import com.edgescheduler.protocol.entity.ProtocolAdapter;
import com.edgescheduler.protocol.entity.ProtocolDriver;
import com.edgescheduler.protocol.service.ProtocolService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/protocol")
@RequiredArgsConstructor
public class ProtocolController {

    private final ProtocolService protocolService;

    @PostMapping("/drivers")
    public Mono<ApiResponse<ProtocolDriverDTO>> registerDriver(@Valid @RequestBody ProtocolDriverDTO driverDTO) {
        return Mono.just(ApiResponse.created(protocolService.registerDriver(driverDTO)));
    }

    @GetMapping("/drivers/{driverId}")
    public Mono<ApiResponse<ProtocolDriverDTO>> getDriver(@PathVariable String driverId) {
        return Mono.just(ApiResponse.success(protocolService.getDriver(driverId)));
    }

    @GetMapping("/drivers")
    public Mono<ApiResponse<List<ProtocolDriver>>> listDrivers(
            @RequestParam(required = false) String protocolType,
            @RequestParam(required = false) String status) {
        return Mono.just(ApiResponse.success(protocolService.listDrivers(protocolType, status)));
    }

    @PutMapping("/drivers/{driverId}/status")
    public Mono<ApiResponse<ProtocolDriverDTO>> updateDriverStatus(
            @PathVariable String driverId,
            @RequestBody Map<String, String> body) {
        String status = body.get("status");
        return Mono.just(ApiResponse.success(protocolService.updateDriverStatus(driverId, status)));
    }

    @DeleteMapping("/drivers/{driverId}")
    public Mono<ApiResponse<Void>> deleteDriver(@PathVariable String driverId) {
        protocolService.deleteDriver(driverId);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/adapters")
    public Mono<ApiResponse<ProtocolAdapterDTO>> createAdapter(@Valid @RequestBody ProtocolAdapterDTO adapterDTO) {
        return Mono.just(ApiResponse.created(protocolService.createAdapter(adapterDTO)));
    }

    @GetMapping("/adapters/{adapterId}")
    public Mono<ApiResponse<ProtocolAdapterDTO>> getAdapter(@PathVariable String adapterId) {
        return Mono.just(ApiResponse.success(protocolService.getAdapter(adapterId)));
    }

    @GetMapping("/adapters")
    public Mono<ApiResponse<List<ProtocolAdapter>>> listAdapters(
            @RequestParam(required = false) String driverId,
            @RequestParam(required = false) String deviceKey,
            @RequestParam(required = false) String status) {
        return Mono.just(ApiResponse.success(protocolService.listAdapters(driverId, deviceKey, status)));
    }

    @PutMapping("/adapters/{adapterId}/config")
    public Mono<ApiResponse<ProtocolAdapterDTO>> updateAdapterConfig(
            @PathVariable String adapterId,
            @RequestBody Map<String, Object> config) {
        return Mono.just(ApiResponse.success(protocolService.updateAdapterConfig(adapterId, config)));
    }

    @PostMapping("/adapters/{adapterId}/connect")
    public Mono<ApiResponse<ProtocolAdapterDTO>> connectAdapter(@PathVariable String adapterId) {
        return Mono.just(ApiResponse.success(protocolService.connectAdapter(adapterId)));
    }

    @PostMapping("/adapters/{adapterId}/disconnect")
    public Mono<ApiResponse<ProtocolAdapterDTO>> disconnectAdapter(@PathVariable String adapterId) {
        return Mono.just(ApiResponse.success(protocolService.disconnectAdapter(adapterId)));
    }

    @PutMapping("/adapters/{adapterId}/status")
    public Mono<ApiResponse<ProtocolAdapterDTO>> updateAdapterStatus(
            @PathVariable String adapterId,
            @RequestBody Map<String, String> body) {
        String status = body.get("status");
        return Mono.just(ApiResponse.success(protocolService.updateAdapterStatus(adapterId, status)));
    }

    @DeleteMapping("/adapters/{adapterId}")
    public Mono<ApiResponse<Void>> deleteAdapter(@PathVariable String adapterId) {
        protocolService.deleteAdapter(adapterId);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/drivers/{driverId}/convert")
    public Mono<ApiResponse<Map<String, Object>>> convertData(
            @PathVariable String driverId,
            @RequestBody Map<String, Object> rawData) {
        return Mono.just(ApiResponse.success(protocolService.convertData(driverId, rawData)));
    }

    @PostMapping("/adapters/{adapterId}/command")
    public Mono<ApiResponse<Map<String, Object>>> sendCommand(
            @PathVariable String adapterId,
            @RequestBody Map<String, Object> command) {
        return Mono.just(ApiResponse.success(protocolService.sendCommand(adapterId, command)));
    }

    @PostMapping("/adapters/{adapterId}/message")
    public Mono<ApiResponse<Void>> recordMessage(
            @PathVariable String adapterId,
            @RequestBody Map<String, Object> body) {
        boolean success = (Boolean) body.getOrDefault("success", true);
        String error = (String) body.get("error");
        protocolService.recordMessage(adapterId, success, error);
        return Mono.just(ApiResponse.success(null));
    }

    @GetMapping("/devices/{deviceKey}/adapters")
    public Mono<ApiResponse<List<ProtocolAdapter>>> getDeviceAdapters(@PathVariable String deviceKey) {
        return Mono.just(ApiResponse.success(protocolService.getDeviceAdapters(deviceKey)));
    }

    @GetMapping("/adapters/{adapterId}/metrics")
    public Mono<ApiResponse<Map<String, Object>>> getAdapterMetrics(@PathVariable String adapterId) {
        return Mono.just(ApiResponse.success(protocolService.getAdapterMetrics(adapterId)));
    }
}
