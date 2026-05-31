package com.edgescheduler.modules.ota.controller;

import com.edgescheduler.common.Result;
import com.edgescheduler.modules.ota.domain.FirmwarePackage;
import com.edgescheduler.modules.ota.domain.UpgradeTask;
import com.edgescheduler.modules.ota.service.OTAService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/ota")
@RequiredArgsConstructor
public class OTAController {

    private final OTAService otaService;

    @PostMapping("/firmware")
    public Mono<Result<FirmwarePackage>> uploadFirmware(@RequestBody Map<String, Object> request) {
        String packageName = (String) request.get("packageName");
        String version = (String) request.get("version");
        String deviceModel = (String) request.get("deviceModel");
        Integer size = (Integer) request.get("size");
        String checksum = (String) request.get("checksum");
        String storagePath = (String) request.get("storagePath");
        String description = (String) request.getOrDefault("description", "");

        return otaService.uploadFirmware(packageName, version, deviceModel, size, checksum, storagePath, description)
                .map(Result::success);
    }

    @PostMapping("/firmware/{firmwareId}/publish")
    public Mono<Result<FirmwarePackage>> publishFirmware(@PathVariable String firmwareId) {
        return otaService.publishFirmware(firmwareId)
                .map(Result::success);
    }

    @GetMapping("/firmware")
    public Flux<Result<FirmwarePackage>> getFirmware(
            @RequestParam(required = false) String deviceModel) {
        return otaService.getFirmware(deviceModel)
                .map(Result::success);
    }

    @PostMapping("/diff")
    public Mono<Result<Map<String, Object>>> generateDiffPackage(
            @RequestParam String fromVersion,
            @RequestParam String toVersion,
            @RequestParam String deviceModel) {
        return otaService.generateDiffPackage(fromVersion, toVersion, deviceModel)
                .map(Result::success);
    }

    @PostMapping("/upgrade-tasks")
    public Mono<Result<UpgradeTask>> createUpgradeTask(@RequestBody Map<String, Object> request) {
        String firmwareId = (String) request.get("firmwareId");
        String deviceId = (String) request.get("deviceId");
        String upgradeType = (String) request.getOrDefault("upgradeType", "full");
        String diffPackagePath = (String) request.get("diffPackagePath");
        Integer grayscaleGroup = (Integer) request.getOrDefault("grayscaleGroup", 0);
        Integer priority = (Integer) request.getOrDefault("priority", 5);
        Map<String, Object> metadata = (Map<String, Object>) request.getOrDefault("metadata", Map.of());

        return otaService.createUpgradeTask(firmwareId, deviceId, upgradeType, diffPackagePath, grayscaleGroup, priority, metadata)
                .map(Result::success);
    }

    @GetMapping("/upgrade-tasks")
    public Flux<Result<UpgradeTask>> getUpgradeTasks(
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) String status) {
        return otaService.getUpgradeTasks(deviceId, status)
                .map(Result::success);
    }

    @PostMapping("/upgrade-tasks/{taskId}/confirm")
    public Mono<Result<UpgradeTask>> confirmUpgrade(@PathVariable String taskId) {
        return otaService.confirmUpgrade(taskId)
                .map(Result::success);
    }

    @PostMapping("/upgrade-tasks/{taskId}/complete")
    public Mono<Result<UpgradeTask>> completeUpgrade(
            @PathVariable String taskId,
            @RequestParam boolean success,
            @RequestParam(required = false) String errorDetail) {
        return otaService.completeUpgrade(taskId, success, errorDetail)
                .map(Result::success);
    }

    @PostMapping("/upgrade-tasks/{taskId}/rollback")
    public Mono<Result<UpgradeTask>> rollbackUpgrade(@PathVariable String taskId) {
        return otaService.rollbackUpgrade(taskId)
                .map(Result::success);
    }

    @GetMapping("/grayscale/{group}")
    public Flux<Result<String>> getGrayscaleDevices(@PathVariable Integer group) {
        return otaService.getGrayscaleDevices(group)
                .map(Result::success);
    }
}
