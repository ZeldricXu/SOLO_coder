package com.edgescheduler.modules.ota.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.edgescheduler.common.exception.BusinessException;
import com.edgescheduler.common.util.IdGenerator;
import com.edgescheduler.modules.ota.domain.FirmwarePackage;
import com.edgescheduler.modules.ota.domain.UpgradeTask;
import com.edgescheduler.modules.ota.mapper.FirmwarePackageMapper;
import com.edgescheduler.modules.ota.mapper.UpgradeTaskMapper;
import com.edgescheduler.modules.device.mapper.DeviceInfoMapper;
import com.edgescheduler.modules.device.domain.DeviceInfo;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class OTAService {

    private final FirmwarePackageMapper firmwarePackageMapper;
    private final UpgradeTaskMapper upgradeTaskMapper;
    private final DeviceInfoMapper deviceInfoMapper;
    private final ReactiveRedisTemplate<String, Object> redisTemplate;
    private final MeterRegistry meterRegistry;

    private final Map<String, UpgradeTask> runningUpgrades = new ConcurrentHashMap<>();
    private static final int MAX_CONCURRENT_UPGRADES = 50;

    @Transactional(rollbackFor = Exception.class)
    public Mono<FirmwarePackage> uploadFirmware(FirmwarePackage firmware) {
        firmware.setFirmwareId(IdGenerator.generateFirmwareId());
        firmware.setReleaseStatus("DRAFT");

        if (firmware.getDeltaFromVersion() != null && !firmware.getDeltaFromVersion().isEmpty()) {
            firmware.setPackageType("DELTA");
            generateDeltaPackage(firmware);
        } else {
            firmware.setPackageType("FULL");
        }

        firmware.setPackageMd5(calculateMD5(firmware.getFirmwareName() + firmware.getFirmwareVersion()));
        firmwarePackageMapper.insert(firmware);
        updateMetrics("firmware_uploaded");
        return Mono.just(firmware);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<FirmwarePackage> publishFirmware(String firmwareId) {
        FirmwarePackage firmware = firmwarePackageMapper.selectOne(
                new LambdaQueryWrapper<FirmwarePackage>().eq(FirmwarePackage::getFirmwareId, firmwareId));
        if (firmware == null) {
            return Mono.error(new BusinessException("固件包不存在"));
        }

        firmware.setReleaseStatus("PUBLISHED");
        firmware.setReleasedAt(LocalDateTime.now());
        firmwarePackageMapper.updateById(firmware);

        updateMetrics("firmware_published");
        return Mono.just(firmware);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<List<UpgradeTask>> createUpgradeTasks(String firmwareId, List<String> deviceIds,
                                                       String grayscaleGroup, Integer maxRetries) {
        FirmwarePackage firmware = firmwarePackageMapper.selectOne(
                new LambdaQueryWrapper<FirmwarePackage>().eq(FirmwarePackage::getFirmwareId, firmwareId));
        if (firmware == null) {
            return Mono.error(new BusinessException("固件包不存在"));
        }

        if (!"PUBLISHED".equals(firmware.getReleaseStatus())) {
            return Mono.error(new BusinessException("固件包未发布"));
        }

        List<UpgradeTask> tasks = new ArrayList<>();
        for (String deviceId : deviceIds) {
            DeviceInfo device = deviceInfoMapper.selectOne(
                    new LambdaQueryWrapper<DeviceInfo>().eq(DeviceInfo::getDeviceId, deviceId));
            if (device == null) {
                log.warn("Device not found: {}", deviceId);
                continue;
            }

            UpgradeTask existingTask = upgradeTaskMapper.selectOne(
                    new LambdaQueryWrapper<UpgradeTask>()
                            .eq(UpgradeTask::getDeviceId, deviceId)
                            .in(UpgradeTask::getUpgradeStatus, "PENDING", "DOWNLOADING", "UPGRADING"));
            if (existingTask != null) {
                log.warn("Device already has an active upgrade task: {}", deviceId);
                continue;
            }

            UpgradeTask task = new UpgradeTask();
            task.setTaskId(IdGenerator.generateTaskId());
            task.setFirmwareId(firmwareId);
            task.setDeviceId(deviceId);
            task.setUpgradeStatus("PENDING");
            task.setUpgradePhase("INIT");
            task.setProgress(0);
            task.setGrayscaleGroup(grayscaleGroup);
            task.setRetryCount(0);
            task.setMaxRetries(maxRetries != null ? maxRetries : 3);
            task.setOriginalVersion(device.getFirmwareVersion());
            task.setTargetVersion(firmware.getFirmwareVersion());
            task.setScheduledTime(LocalDateTime.now());

            upgradeTaskMapper.insert(task);
            tasks.add(task);
        }

        updateMetrics("upgrade_tasks_created");
        return Mono.just(tasks);
    }

    @Scheduled(fixedDelay = 5000)
    public void processUpgradeTasks() {
        int runningCount = runningUpgrades.size();
        int availableSlots = MAX_CONCURRENT_UPGRADES - runningCount;

        if (availableSlots <= 0) {
            return;
        }

        List<UpgradeTask> pendingTasks = upgradeTaskMapper.selectList(
                new LambdaQueryWrapper<UpgradeTask>()
                        .eq(UpgradeTask::getUpgradeStatus, "PENDING")
                        .orderByAsc(UpgradeTask::getScheduledTime)
                        .last("LIMIT " + availableSlots));

        for (UpgradeTask task : pendingTasks) {
            if (!runningUpgrades.containsKey(task.getTaskId())) {
                runningUpgrades.put(task.getTaskId(), task);
                executeUpgrade(task);
            }
        }
    }

    private void executeUpgrade(UpgradeTask task) {
        new Thread(() -> {
            try {
                task.setUpgradeStatus("DOWNLOADING");
                task.setUpgradePhase("DOWNLOAD");
                task.setStartTime(LocalDateTime.now());
                upgradeTaskMapper.updateById(task);
                simulateProgress(task, 0, 40, 1000);

                task.setUpgradeStatus("UPGRADING");
                task.setUpgradePhase("INSTALL");
                upgradeTaskMapper.updateById(task);
                simulateProgress(task, 40, 90, 1500);

                task.setUpgradeStatus("VERIFYING");
                task.setUpgradePhase("VERIFY");
                upgradeTaskMapper.updateById(task);
                simulateProgress(task, 90, 100, 500);

                task.setUpgradeStatus("COMPLETED");
                task.setUpgradePhase("DONE");
                task.setProgress(100);
                task.setCompletedTime(LocalDateTime.now());
                upgradeTaskMapper.updateById(task);

                updateDeviceFirmwareVersion(task.getDeviceId(), task.getTargetVersion());

                updateMetrics("upgrade_completed");
            } catch (Exception e) {
                log.error("Upgrade task failed: {}", task.getTaskId(), e);
                handleUpgradeFailure(task, e.getMessage());
            } finally {
                runningUpgrades.remove(task.getTaskId());
            }
        }).start();
    }

    private void simulateProgress(UpgradeTask task, int startProgress, int endProgress, long delay)
            throws InterruptedException {
        int steps = 5;
        for (int i = 1; i <= steps; i++) {
            int progress = startProgress + ((endProgress - startProgress) * i / steps);
            task.setProgress(progress);
            upgradeTaskMapper.updateById(task);
            Thread.sleep(delay / steps);
        }
    }

    private void handleUpgradeFailure(UpgradeTask task, String errorMessage) {
        task.setUpgradeStatus("FAILED");
        task.setErrorMessage(errorMessage);
        task.setCompletedTime(LocalDateTime.now());

        if (task.getRetryCount() < task.getMaxRetries()) {
            task.setRetryCount(task.getRetryCount() + 1);
            task.setUpgradeStatus("PENDING");
            task.setUpgradePhase("RETRY");
            task.setProgress(0);
            upgradeTaskMapper.updateById(task);
            log.info("Retrying upgrade task: {} (attempt {}/{})",
                    task.getTaskId(), task.getRetryCount(), task.getMaxRetries());
            updateMetrics("upgrade_retried");
        } else {
            task.setRollbackReason("Max retries exceeded");
            task.setRollbackTime(LocalDateTime.now());
            upgradeTaskMapper.updateById(task);
            initiateRollback(task);
            updateMetrics("upgrade_failed");
        }
    }

    private void initiateRollback(UpgradeTask task) {
        log.info("Initiating rollback for device: {} to version: {}",
                task.getDeviceId(), task.getOriginalVersion());

        new Thread(() -> {
            try {
                Thread.sleep(1000);
                updateDeviceFirmwareVersion(task.getDeviceId(), task.getOriginalVersion());
                log.info("Rollback completed for device: {}", task.getDeviceId());
                updateMetrics("rollback_completed");
            } catch (Exception e) {
                log.error("Rollback failed for device: {}", task.getDeviceId(), e);
                updateMetrics("rollback_failed");
            }
        }).start();
    }

    private void updateDeviceFirmwareVersion(String deviceId, String version) {
        DeviceInfo device = deviceInfoMapper.selectOne(
                new LambdaQueryWrapper<DeviceInfo>().eq(DeviceInfo::getDeviceId, deviceId));
        if (device != null) {
            device.setFirmwareVersion(version);
            deviceInfoMapper.updateById(device);
        }
    }

    private void generateDeltaPackage(FirmwarePackage firmware) {
        FirmwarePackage baseFirmware = firmwarePackageMapper.selectOne(
                new LambdaQueryWrapper<FirmwarePackage>()
                        .eq(FirmwarePackage::getDeviceModel, firmware.getDeviceModel())
                        .eq(FirmwarePackage::getFirmwareVersion, firmware.getDeltaFromVersion()));

        if (baseFirmware != null && firmware.getPackageSize() != null && baseFirmware.getPackageSize() != null) {
            firmware.setDeltaPackageSize(Math.abs(firmware.getPackageSize() - baseFirmware.getPackageSize()) / 2);
        } else {
            firmware.setDeltaPackageSize(firmware.getPackageSize() / 3);
        }
    }

    private String calculateMD5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }

    public Flux<FirmwarePackage> getFirmwarePackages(String deviceModel, String releaseStatus) {
        List<FirmwarePackage> packages = firmwarePackageMapper.selectList(
                new LambdaQueryWrapper<FirmwarePackage>()
                        .eq(deviceModel != null, FirmwarePackage::getDeviceModel, deviceModel)
                        .eq(releaseStatus != null, FirmwarePackage::getReleaseStatus, releaseStatus)
                        .orderByDesc(FirmwarePackage::getCreatedAt));
        return Flux.fromIterable(packages);
    }

    public Flux<UpgradeTask> getUpgradeTasks(String deviceId, String upgradeStatus) {
        List<UpgradeTask> tasks = upgradeTaskMapper.selectList(
                new LambdaQueryWrapper<UpgradeTask>()
                        .eq(deviceId != null, UpgradeTask::getDeviceId, deviceId)
                        .eq(upgradeStatus != null, UpgradeTask::getUpgradeStatus, upgradeStatus)
                        .orderByDesc(UpgradeTask::getCreatedAt));
        return Flux.fromIterable(tasks);
    }

    public Mono<UpgradeTask> getUpgradeTask(String taskId) {
        UpgradeTask task = upgradeTaskMapper.selectOne(
                new LambdaQueryWrapper<UpgradeTask>().eq(UpgradeTask::getTaskId, taskId));
        if (task == null) {
            return Mono.error(new BusinessException("升级任务不存在"));
        }
        return Mono.just(task);
    }

    @Transactional(rollbackFor = Exception.class)
    public Mono<Void> cancelUpgrade(String taskId) {
        UpgradeTask task = upgradeTaskMapper.selectOne(
                new LambdaQueryWrapper<UpgradeTask>().eq(UpgradeTask::getTaskId, taskId));
        if (task == null) {
            return Mono.error(new BusinessException("升级任务不存在"));
        }

        if ("PENDING".equals(task.getUpgradeStatus())) {
            task.setUpgradeStatus("CANCELLED");
            upgradeTaskMapper.updateById(task);
            runningUpgrades.remove(taskId);
        }

        return Mono.empty();
    }

    public Mono<Map<String, Object>> getUpgradeStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("runningUpgrades", runningUpgrades.size());
        stats.put("maxConcurrentUpgrades", MAX_CONCURRENT_UPGRADES);

        long totalFirmware = firmwarePackageMapper.selectCount(null);
        stats.put("totalFirmwarePackages", totalFirmware);

        long pendingTasks = upgradeTaskMapper.selectCount(
                new LambdaQueryWrapper<UpgradeTask>().eq(UpgradeTask::getUpgradeStatus, "PENDING"));
        stats.put("pendingTasks", pendingTasks);

        return Mono.just(stats);
    }

    private void updateMetrics(String action) {
        meterRegistry.counter("edge_scheduler_ota_operations_total", "action", action).increment();
    }
}
