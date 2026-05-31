package com.edgescheduler.ota.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edgescheduler.common.dto.ApiResponse;
import com.edgescheduler.ota.dto.DiffPackageRequest;
import com.edgescheduler.ota.dto.FirmwareDTO;
import com.edgescheduler.ota.dto.OtaJobDTO;
import com.edgescheduler.ota.entity.Firmware;
import com.edgescheduler.ota.entity.OtaDeviceUpgrade;
import com.edgescheduler.ota.entity.OtaJob;
import com.edgescheduler.ota.service.OtaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ota")
@RequiredArgsConstructor
public class OtaController {

    private final OtaService otaService;

    @PostMapping("/firmwares")
    public Mono<ApiResponse<FirmwareDTO>> createFirmware(@Valid @RequestBody FirmwareDTO firmwareDTO) {
        return Mono.just(ApiResponse.created(otaService.createFirmware(firmwareDTO)));
    }

    @GetMapping("/firmwares/{firmwareId}")
    public Mono<ApiResponse<FirmwareDTO>> getFirmware(@PathVariable String firmwareId) {
        return Mono.just(ApiResponse.success(otaService.getFirmware(firmwareId)));
    }

    @GetMapping("/firmwares")
    public Mono<ApiResponse<IPage<FirmwareDTO>>> listFirmwares(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String productKey,
            @RequestParam(required = false) String status) {
        Page<Firmware> pageParam = new Page<>(page, size);
        return Mono.just(ApiResponse.success(otaService.listFirmwares(pageParam, productKey, status)));
    }

    @PostMapping("/firmwares/{firmwareId}/publish")
    public Mono<ApiResponse<FirmwareDTO>> publishFirmware(@PathVariable String firmwareId) {
        return Mono.just(ApiResponse.success(otaService.publishFirmware(firmwareId)));
    }

    @PostMapping("/firmwares/{firmwareId}/retire")
    public Mono<ApiResponse<FirmwareDTO>> retireFirmware(@PathVariable String firmwareId) {
        return Mono.just(ApiResponse.success(otaService.retireFirmware(firmwareId)));
    }

    @DeleteMapping("/firmwares/{firmwareId}")
    public Mono<ApiResponse<Void>> deleteFirmware(@PathVariable String firmwareId) {
        otaService.deleteFirmware(firmwareId);
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/firmwares/diff")
    public Mono<ApiResponse<Map<String, Object>>> generateDiffPackage(
            @Valid @RequestBody DiffPackageRequest request) {
        return Mono.just(ApiResponse.success(otaService.generateDiffPackage(request)));
    }

    @PostMapping("/jobs")
    public Mono<ApiResponse<OtaJobDTO>> createOtaJob(@Valid @RequestBody OtaJobDTO otaJobDTO) {
        return Mono.just(ApiResponse.created(otaService.createOtaJob(otaJobDTO)));
    }

    @GetMapping("/jobs/{jobId}")
    public Mono<ApiResponse<OtaJobDTO>> getOtaJob(@PathVariable String jobId) {
        return Mono.just(ApiResponse.success(otaService.getOtaJob(jobId)));
    }

    @GetMapping("/jobs")
    public Mono<ApiResponse<IPage<OtaJobDTO>>> listOtaJobs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String productKey,
            @RequestParam(required = false) String status) {
        Page<OtaJob> pageParam = new Page<>(page, size);
        return Mono.just(ApiResponse.success(otaService.listOtaJobs(pageParam, productKey, status)));
    }

    @PostMapping("/jobs/{jobId}/start")
    public Mono<ApiResponse<OtaJobDTO>> startOtaJob(@PathVariable String jobId) {
        return Mono.just(ApiResponse.success(otaService.startOtaJob(jobId)));
    }

    @PostMapping("/jobs/{jobId}/pause")
    public Mono<ApiResponse<OtaJobDTO>> pauseOtaJob(@PathVariable String jobId) {
        return Mono.just(ApiResponse.success(otaService.pauseOtaJob(jobId)));
    }

    @PostMapping("/jobs/{jobId}/cancel")
    public Mono<ApiResponse<OtaJobDTO>> cancelOtaJob(@PathVariable String jobId) {
        return Mono.just(ApiResponse.success(otaService.cancelOtaJob(jobId)));
    }

    @PostMapping("/jobs/{jobId}/rollback")
    public Mono<ApiResponse<OtaJobDTO>> rollbackOtaJob(@PathVariable String jobId) {
        return Mono.just(ApiResponse.success(otaService.rollbackOtaJob(jobId)));
    }

    @GetMapping("/jobs/{jobId}/statistics")
    public Mono<ApiResponse<Map<String, Object>>> getOtaJobStatistics(@PathVariable String jobId) {
        return Mono.just(ApiResponse.success(otaService.getOtaJobStatistics(jobId)));
    }

    @GetMapping("/jobs/{jobId}/devices")
    public Mono<ApiResponse<List<OtaDeviceUpgrade>>> getDeviceUpgrades(@PathVariable String jobId) {
        return Mono.just(ApiResponse.success(otaService.getDeviceUpgrades(jobId)));
    }

    @GetMapping("/jobs/{jobId}/devices/{deviceKey}")
    public Mono<ApiResponse<OtaDeviceUpgrade>> getDeviceUpgrade(
            @PathVariable String jobId,
            @PathVariable String deviceKey) {
        return Mono.just(ApiResponse.success(otaService.getDeviceUpgrade(jobId, deviceKey)));
    }

    @PutMapping("/jobs/{jobId}/devices/{deviceKey}/progress")
    public Mono<ApiResponse<OtaDeviceUpgrade>> updateDeviceUpgradeProgress(
            @PathVariable String jobId,
            @PathVariable String deviceKey,
            @RequestBody Map<String, Object> body) {
        String status = (String) body.get("status");
        Integer progress = body.get("progress") != null ?
                ((Number) body.get("progress")).intValue() : null;
        String errorCode = (String) body.get("errorCode");
        String errorMessage = (String) body.get("errorMessage");

        return Mono.just(ApiResponse.success(
                otaService.updateDeviceUpgradeProgress(jobId, deviceKey, status, progress, errorCode, errorMessage)));
    }
}
