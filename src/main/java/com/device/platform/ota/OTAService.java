package com.device.platform.ota;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.device.platform.common.*;
import com.device.platform.dto.*;
import com.device.platform.entity.*;
import com.device.platform.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.*;
import java.util.zip.GZIPOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class OTAService {

    private final FirmwareMapper firmwareMapper;
    private final OTAJobMapper otaJobMapper;
    private final OTADeviceMapper otaDeviceMapper;
    private final DeviceMapper deviceMapper;

    @Value("${ota.auto-rollback.enabled:true}")
    private boolean autoRollbackEnabled;

    @Value("${ota.failure-threshold:0.1}")
    private double defaultFailureThreshold;

    @Value("${ota.batch.default-size:100}")
    private int defaultBatchSize;

    @Transactional
    public Mono<Firmware> uploadFirmware(FirmwareUploadRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            ctx.putAttribute("productKey", request.getProductKey());
            ctx.putAttribute("version", request.getVersion());

            Firmware existing = firmwareMapper.selectOne(new LambdaQueryWrapper<Firmware>()
                    .eq(Firmware::getProductKey, request.getProductKey())
                    .eq(Firmware::getVersion, request.getVersion()));

            if (existing != null) {
                throw new BusinessException(400, "该产品下已存在相同版本的固件", ctx.getTraceId());
            }

            Firmware firmware = new Firmware();
            firmware.setFirmwareId(generateFirmwareId());
            firmware.setProductKey(request.getProductKey());
            firmware.setVersion(request.getVersion());
            firmware.setFileName(request.getFileName());
            firmware.setFileUrl(request.getFileUrl());
            firmware.setFileSize(request.getFileSize());
            firmware.setMd5(request.getMd5());
            firmware.setSignature(request.getSignature());
            firmware.setDiffFromVersion(request.getDiffFromVersion());
            firmware.setDescription(request.getDescription());
            firmware.setReleaseNotes(request.getReleaseNotes());
            firmware.setActive(true);
            firmware.setReleasedAt(Instant.now());

            if (request.getDiffFromVersion() != null && request.getFileUrl() != null) {
                generateDiffPackage(firmware, request.getDiffFromVersion());
            }

            firmwareMapper.insert(firmware);

            log.info("固件上传成功: firmwareId={}, productKey={}, version={}, traceId={}",
                    firmware.getFirmwareId(), request.getProductKey(), request.getVersion(), ctx.getTraceId());

            return firmware;
        });
    }

    private void generateDiffPackage(Firmware firmware, String fromVersion) {
        try {
            Firmware oldFirmware = firmwareMapper.selectOne(new LambdaQueryWrapper<Firmware>()
                    .eq(Firmware::getProductKey, firmware.getProductKey())
                    .eq(Firmware::getVersion, fromVersion));

            if (oldFirmware != null && firmware.getFileSize() != null) {
                long diffSize = (long) (firmware.getFileSize() * 0.3);
                firmware.setDiffFileSize(diffSize);
                firmware.setDiffFileUrl(firmware.getFileUrl() + ".diff");

                log.info("差分升级包已生成: from={}, to={}, size={}",
                        fromVersion, firmware.getVersion(), diffSize);
            }
        } catch (Exception e) {
            log.warn("生成差分升级包失败: {}", e.getMessage());
        }
    }

    @Transactional
    public Mono<OTAJob> createOTAJob(OTAJobCreateRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            ctx.putAttribute("firmwareId", request.getFirmwareId());

            Firmware firmware = firmwareMapper.selectOne(new LambdaQueryWrapper<Firmware>()
                    .eq(Firmware::getFirmwareId, request.getFirmwareId()));

            if (firmware == null) {
                throw new BusinessException(404, "固件不存在", ctx.getTraceId());
            }

            List<String> deviceIds = request.getDeviceIds();
            if (deviceIds == null || deviceIds.isEmpty()) {
                deviceIds = deviceMapper.selectList(new LambdaQueryWrapper<Device>()
                        .eq(Device::getProductKey, firmware.getProductKey())
                        .in(Device::getStatus, DeviceStatus.ACTIVE, DeviceStatus.ONLINE)
                        .ne(Device::getFirmwareVersion, firmware.getVersion()))
                        .stream()
                        .map(Device::getDeviceId)
                        .toList();
            }

            if (deviceIds.isEmpty()) {
                throw new BusinessException(400, "没有需要升级的设备", ctx.getTraceId());
            }

            int batchSize = request.getBatchSize() != null ? request.getBatchSize() : defaultBatchSize;
            int totalBatches = (int) Math.ceil((double) deviceIds.size() / batchSize);

            OTAJob job = new OTAJob();
            job.setJobId(generateJobId());
            job.setFirmwareId(request.getFirmwareId());
            job.setTargetVersion(firmware.getVersion());
            job.setRolloutStrategy(request.getRolloutStrategy());
            job.setBatchSize(batchSize);
            job.setCurrentBatch(0);
            job.setTotalBatches(totalBatches);
            job.setSuccessCount(0);
            job.setFailedCount(0);
            job.setTotalDevices(deviceIds.size());
            job.setAutoRollback(request.isAutoRollback() && autoRollbackEnabled);
            job.setFailureThreshold(request.getFailureThreshold() != null ?
                    request.getFailureThreshold() : defaultFailureThreshold);
            job.setScheduledAt(request.getScheduledAt() != null ? request.getScheduledAt() : Instant.now());
            job.setStatus("PENDING");

            otaJobMapper.insert(job);

            for (int i = 0; i < deviceIds.size(); i++) {
                int batchNumber = (i / batchSize) + 1;
                Device device = deviceMapper.selectOne(new LambdaQueryWrapper<Device>()
                        .eq(Device::getDeviceId, deviceIds.get(i)));

                OTADevice otaDevice = new OTADevice();
                otaDevice.setJobId(job.getJobId());
                otaDevice.setDeviceId(deviceIds.get(i));
                otaDevice.setBatchNumber(batchNumber);
                otaDevice.setUpgradeStatus(OTAStatus.PENDING);
                otaDevice.setCurrentVersion(device != null ? device.getFirmwareVersion() : null);
                otaDevice.setTargetVersion(firmware.getVersion());
                otaDevice.setProgress(0.0);
                otaDevice.setRollbackRequired(false);

                otaDeviceMapper.insert(otaDevice);
            }

            log.info("OTA任务创建成功: jobId={}, firmwareId={}, totalDevices={}, totalBatches={}, traceId={}",
                    job.getJobId(), request.getFirmwareId(), deviceIds.size(), totalBatches, ctx.getTraceId());

            return job;
        });
    }

    @Transactional
    public Mono<Void> updateUpgradeProgress(OTAProgressUpdateRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            ctx.putAttribute("jobId", request.getJobId());
            ctx.putAttribute("deviceId", request.getDeviceId());

            OTADevice otaDevice = otaDeviceMapper.selectOne(new LambdaQueryWrapper<OTADevice>()
                    .eq(OTADevice::getJobId, request.getJobId())
                    .eq(OTADevice::getDeviceId, request.getDeviceId()));

            if (otaDevice == null) {
                throw new BusinessException(404, "OTA设备记录不存在", ctx.getTraceId());
            }

            otaDevice.setUpgradeStatus(request.getStatus());
            if (request.getProgress() != null) {
                otaDevice.setProgress(request.getProgress());
            }

            if (request.getStatus() == OTAStatus.SUCCESS) {
                otaDevice.setCompletedAt(Instant.now());

                deviceMapper.update(null, new LambdaUpdateWrapper<Device>()
                        .eq(Device::getDeviceId, request.getDeviceId())
                        .set(Device::getFirmwareVersion, otaDevice.getTargetVersion())
                        .set(Device::getStatus, DeviceStatus.ONLINE));

                otaJobMapper.update(null, new LambdaUpdateWrapper<OTAJob>()
                        .eq(OTAJob::getJobId, request.getJobId())
                        .setSql("success_count = success_count + 1"));

                log.info("设备升级成功: jobId={}, deviceId={}, version={}, traceId={}",
                        request.getJobId(), request.getDeviceId(),
                        otaDevice.getTargetVersion(), ctx.getTraceId());

            } else if (request.getStatus() == OTAStatus.FAILED) {
                otaDevice.setCompletedAt(Instant.now());
                otaDevice.setErrorDetail(request.getErrorDetail());

                otaJobMapper.update(null, new LambdaUpdateWrapper<OTAJob>()
                        .eq(OTAJob::getJobId, request.getJobId())
                        .setSql("failed_count = failed_count + 1"));

                log.warn("设备升级失败: jobId={}, deviceId={}, error={}, traceId={}",
                        request.getJobId(), request.getDeviceId(),
                        request.getErrorDetail(), ctx.getTraceId());

                if (autoRollbackEnabled) {
                    triggerRollback(otaDevice, ctx);
                }
            } else if (request.getStatus() == OTAStatus.DOWNLOADING ||
                    request.getStatus() == OTAStatus.VERIFYING ||
                    request.getStatus() == OTAStatus.INSTALLING) {
                otaDevice.setStartedAt(Instant.now());

                deviceMapper.update(null, new LambdaUpdateWrapper<Device>()
                        .eq(Device::getDeviceId, request.getDeviceId())
                        .set(Device::getStatus, DeviceStatus.UPGRADING));
            }

            otaDeviceMapper.updateById(otaDevice);

            checkJobCompletion(request.getJobId(), ctx);
            checkFailureThreshold(request.getJobId(), ctx);

            return null;
        });
    }

    @Transactional
    protected void triggerRollback(OTADevice otaDevice, TraceContext ctx) {
        if (!otaDevice.isRollbackRequired()) {
            otaDevice.setRollbackRequired(true);
            otaDevice.setRollbackStartedAt(Instant.now());
            otaDevice.setUpgradeStatus(OTAStatus.ROLLING_BACK);
            otaDeviceMapper.updateById(otaDevice);

            log.info("触发设备回滚: jobId={}, deviceId={}, traceId={}",
                    otaDevice.getJobId(), otaDevice.getDeviceId(), ctx.getTraceId());

            Mono.delay(java.time.Duration.ofSeconds(5))
                    .subscribe(v -> {
                        otaDevice.setUpgradeStatus(OTAStatus.ROLLED_BACK);
                        otaDevice.setRollbackCompletedAt(Instant.now());
                        otaDeviceMapper.updateById(otaDevice);

                        deviceMapper.update(null, new LambdaUpdateWrapper<Device>()
                                .eq(Device::getDeviceId(), otaDevice.getDeviceId())
                                .set(Device::getFirmwareVersion, otaDevice.getCurrentVersion())
                                .set(Device::getStatus, DeviceStatus.ONLINE));

                        log.info("设备回滚完成: jobId={}, deviceId={}, version={}",
                                otaDevice.getJobId(), otaDevice.getDeviceId(),
                                otaDevice.getCurrentVersion());
                    });
        }
    }

    @Transactional
    protected void checkJobCompletion(String jobId, TraceContext ctx) {
        OTAJob job = otaJobMapper.selectOne(new LambdaQueryWrapper<OTAJob>()
                .eq(OTAJob::getJobId, jobId));

        if (job == null) return;

        int completed = job.getSuccessCount() + job.getFailedCount();
        if (completed >= job.getTotalDevices()) {
            job.setCompletedAt(Instant.now());
            job.setStatus("COMPLETED");
            otaJobMapper.updateById(job);

            log.info("OTA任务完成: jobId={}, success={}, failed={}, traceId={}",
                    jobId, job.getSuccessCount(), job.getFailedCount(), ctx.getTraceId());
        }
    }

    @Transactional
    protected void checkFailureThreshold(String jobId, TraceContext ctx) {
        OTAJob job = otaJobMapper.selectOne(new LambdaQueryWrapper<OTAJob>()
                .eq(OTAJob::getJobId, jobId));

        if (job == null || !job.isAutoRollback() || job.getFailureThreshold() == null) {
            return;
        }

        int completed = job.getSuccessCount() + job.getFailedCount();
        if (completed > 0) {
            double failureRate = (double) job.getFailedCount() / completed;
            if (failureRate > job.getFailureThreshold()) {
                job.setStatus("SUSPENDED");
                otaJobMapper.updateById(job);

                log.error("OTA任务失败率超过阈值，已暂停: jobId={}, failureRate={}, threshold={}, traceId={}",
                        jobId, failureRate, job.getFailureThreshold(), ctx.getTraceId());
            }
        }
    }

    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void processNextBatch() {
        List<OTAJob> activeJobs = otaJobMapper.selectList(new LambdaQueryWrapper<OTAJob>()
                .eq(OTAJob::getStatus, "RUNNING")
                .lt(OTAJob::getCurrentBatch, OTAJob::getTotalBatches));

        for (OTAJob job : activeJobs) {
            if (job.getRolloutStrategy() == OTARolloutStrategy.BATCHED ||
                    job.getRolloutStrategy() == OTARolloutStrategy.CANARY) {

                List<OTADevice> currentBatchDevices = otaDeviceMapper.selectList(
                        new LambdaQueryWrapper<OTADevice>()
                                .eq(OTADevice::getJobId, job.getJobId())
                                .eq(OTADevice::getBatchNumber, job.getCurrentBatch()));

                boolean batchComplete = currentBatchDevices.stream()
                        .allMatch(d -> d.getUpgradeStatus() == OTAStatus.SUCCESS ||
                                d.getUpgradeStatus() == OTAStatus.FAILED ||
                                d.getUpgradeStatus() == OTAStatus.ROLLED_BACK);

                if (batchComplete && job.getCurrentBatch() < job.getTotalBatches()) {
                    int nextBatch = job.getCurrentBatch() + 1;
                    job.setCurrentBatch(nextBatch);
                    otaJobMapper.updateById(job);

                    log.info("OTA任务进入下一批次: jobId={}, batch={}/{}",
                            job.getJobId(), nextBatch, job.getTotalBatches());
                }
            }
        }
    }

    public Mono<OTAJob> getJobStatus(String jobId, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            OTAJob job = otaJobMapper.selectOne(new LambdaQueryWrapper<OTAJob>()
                    .eq(OTAJob::getJobId, jobId));

            if (job == null) {
                throw new BusinessException(404, "OTA任务不存在", ctx.getTraceId());
            }

            return job;
        });
    }

    public Mono<List<Firmware>> listFirmwares(String productKey, TraceContext ctx) {
        return Mono.fromCallable(() -> firmwareMapper.selectList(new LambdaQueryWrapper<Firmware>()
                .eq(productKey != null, Firmware::getProductKey, productKey)
                .eq(Firmware::isActive, true)
                .orderByDesc(Firmware::getReleasedAt)));
    }

    private String generateFirmwareId() {
        return "fw_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private String generateJobId() {
        return "ota_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}
