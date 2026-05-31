package com.edgescheduler.ota.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.edgescheduler.common.exception.BusinessException;
import com.edgescheduler.device.entity.Device;
import com.edgescheduler.device.mapper.DeviceMapper;
import com.edgescheduler.ota.dto.DiffPackageRequest;
import com.edgescheduler.ota.dto.FirmwareDTO;
import com.edgescheduler.ota.dto.OtaJobDTO;
import com.edgescheduler.ota.entity.Firmware;
import com.edgescheduler.ota.entity.OtaDeviceUpgrade;
import com.edgescheduler.ota.entity.OtaJob;
import com.edgescheduler.ota.mapper.FirmwareMapper;
import com.edgescheduler.ota.mapper.OtaDeviceUpgradeMapper;
import com.edgescheduler.ota.mapper.OtaJobMapper;
import com.edgescheduler.ota.service.OtaService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class OtaServiceImpl implements OtaService {

    private static final BigDecimal DEFAULT_SUCCESS_RATE_THRESHOLD = new BigDecimal("90.00");
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final Set<String> COMPLETED_JOB_STATUSES = Set.of(
            OtaJob.Status.SUCCESS,
            OtaJob.Status.FAILED,
            OtaJob.Status.ROLLEDBACK
    );

    private final FirmwareMapper firmwareMapper;
    private final OtaJobMapper otaJobMapper;
    private final OtaDeviceUpgradeMapper deviceUpgradeMapper;
    private final DeviceMapper deviceMapper;
    private final MeterRegistry meterRegistry;

    private final Map<String, ReentrantLock> jobLocks = new ConcurrentHashMap<>();

    @Value("${edge.scheduler.ota.gray-scale-batches:3}")
    private int defaultGrayScaleBatches;

    @Value("${edge.scheduler.ota.rollback-enabled:true}")
    private boolean defaultRollbackEnabled;

    private Counter firmwareCreateCounter;
    private Counter firmwarePublishCounter;
    private Counter firmwareDiffGenerateCounter;
    private Counter otaJobCreateCounter;
    private Counter otaJobStartCounter;
    private Counter otaJobSuccessCounter;
    private Counter otaJobRollbackCounter;
    private Counter deviceUpgradeSuccessCounter;
    private Counter deviceUpgradeFailedCounter;

    @PostConstruct
    public void initMetrics() {
        firmwareCreateCounter = meterRegistry.counter("firmware.create.total");
        firmwarePublishCounter = meterRegistry.counter("firmware.publish.total");
        firmwareDiffGenerateCounter = meterRegistry.counter("firmware.diff.generate.total");
        otaJobCreateCounter = meterRegistry.counter("ota.job.create.total");
        otaJobStartCounter = meterRegistry.counter("ota.job.start.total");
        otaJobSuccessCounter = meterRegistry.counter("ota.job.success.total");
        otaJobRollbackCounter = meterRegistry.counter("ota.job.rollback.total");
        deviceUpgradeSuccessCounter = meterRegistry.counter("ota.device.upgrade.success");
        deviceUpgradeFailedCounter = meterRegistry.counter("ota.device.upgrade.failed");
    }

    @Override
    @Transactional
    public FirmwareDTO createFirmware(FirmwareDTO firmwareDTO) {
        Firmware existing = firmwareMapper.selectByProductKeyAndVersion(
                firmwareDTO.getProductKey(), firmwareDTO.getVersion());
        if (existing != null) {
            throw BusinessException.conflict("Firmware already exists for product: " +
                    firmwareDTO.getProductKey() + ", version: " + firmwareDTO.getVersion());
        }

        Firmware firmware = new Firmware();
        BeanUtils.copyProperties(firmwareDTO, firmware);
        firmware.setFirmwareId("fw_" + IdUtil.getSnowflakeNextIdStr());
        firmware.setStatus(Firmware.Status.DRAFT);

        firmwareMapper.insert(firmware);
        firmwareCreateCounter.increment();
        log.info("Firmware created: {}", firmware.getFirmwareId());

        return convertToFirmwareDTO(firmware);
    }

    @Override
    public FirmwareDTO getFirmware(String firmwareId) {
        return convertToFirmwareDTO(getFirmwareEntity(firmwareId));
    }

    @Override
    public IPage<FirmwareDTO> listFirmwares(Page<Firmware> page, String productKey, String status) {
        LambdaQueryWrapper<Firmware> wrapper = buildFirmwareQueryWrapper(productKey, status);
        return firmwareMapper.selectPage(page, wrapper).convert(this::convertToFirmwareDTO);
    }

    private LambdaQueryWrapper<Firmware> buildFirmwareQueryWrapper(String productKey, String status) {
        LambdaQueryWrapper<Firmware> wrapper = new LambdaQueryWrapper<>();
        if (productKey != null) {
            wrapper.eq(Firmware::getProductKey, productKey);
        }
        if (status != null) {
            wrapper.eq(Firmware::getStatus, status);
        }
        wrapper.orderByDesc(Firmware::getCreatedAt);
        return wrapper;
    }

    @Override
    @Transactional
    public FirmwareDTO publishFirmware(String firmwareId) {
        Firmware firmware = getFirmwareEntity(firmwareId);
        if (!Firmware.Status.DRAFT.equals(firmware.getStatus())) {
            throw BusinessException.badRequest("Only draft firmware can be published");
        }

        firmware.setStatus(Firmware.Status.PUBLISHED);
        firmware.setPublishedAt(LocalDateTime.now());
        firmwareMapper.updateById(firmware);

        firmwarePublishCounter.increment();
        log.info("Firmware published: {}", firmwareId);

        return convertToFirmwareDTO(firmware);
    }

    @Override
    @Transactional
    public FirmwareDTO retireFirmware(String firmwareId) {
        Firmware firmware = getFirmwareEntity(firmwareId);
        firmware.setStatus(Firmware.Status.RETIRED);
        firmwareMapper.updateById(firmware);

        log.info("Firmware retired: {}", firmwareId);
        return convertToFirmwareDTO(firmware);
    }

    @Override
    @Transactional
    public void deleteFirmware(String firmwareId) {
        Firmware firmware = getFirmwareEntity(firmwareId);
        if (Firmware.Status.PUBLISHED.equals(firmware.getStatus())) {
            throw BusinessException.badRequest("Published firmware cannot be deleted");
        }
        firmwareMapper.deleteById(firmware.getId());
        log.info("Firmware deleted: {}", firmwareId);
    }

    @Override
    @Transactional
    public Map<String, Object> generateDiffPackage(DiffPackageRequest request) {
        Firmware fromFirmware = getFirmwareByVersion(request.getProductKey(), request.getFromVersion());
        Firmware toFirmware = getFirmwareByVersion(request.getProductKey(), request.getToVersion());

        String algorithm = request.getAlgorithm() != null ? request.getAlgorithm() : "bsdiff";
        String diffFilePath = buildDiffFilePath(request.getProductKey(), request.getFromVersion(), request.getToVersion());
        long diffSize = calculateDiffSize(fromFirmware.getFileSize(), toFirmware.getFileSize(), algorithm);

        toFirmware.setDiffFromVersion(request.getFromVersion());
        toFirmware.setDiffFilePath(diffFilePath);
        firmwareMapper.updateById(toFirmware);

        firmwareDiffGenerateCounter.increment();
        log.info("Diff package generated: {} -> {}, algorithm: {}", request.getFromVersion(), request.getToVersion(), algorithm);

        return Map.of(
                "diffId", "diff_" + IdUtil.getSnowflakeNextIdStr(),
                "productKey", request.getProductKey(),
                "fromVersion", request.getFromVersion(),
                "toVersion", request.getToVersion(),
                "algorithm", algorithm,
                "diffFilePath", diffFilePath,
                "diffSize", diffSize,
                "compressionRatio", calculateCompressionRatio(fromFirmware.getFileSize(), toFirmware.getFileSize(), diffSize),
                "generatedAt", LocalDateTime.now().toString()
        );
    }

    private String buildDiffFilePath(String productKey, String fromVersion, String toVersion) {
        return String.format("./data/diff/%s_%s_to_%s.patch", productKey, fromVersion, toVersion);
    }

    private Firmware getFirmwareByVersion(String productKey, String version) {
        Firmware firmware = firmwareMapper.selectByProductKeyAndVersion(productKey, version);
        if (firmware == null) {
            throw BusinessException.notFound("Firmware not found: " + version);
        }
        return firmware;
    }

    @Override
    @Transactional
    public OtaJobDTO createOtaJob(OtaJobDTO otaJobDTO) {
        Firmware firmware = getFirmwareEntity(otaJobDTO.getFirmwareId());
        if (!Firmware.Status.PUBLISHED.equals(firmware.getStatus())) {
            throw BusinessException.badRequest("Only published firmware can be used for OTA");
        }

        OtaJob job = buildOtaJob(otaJobDTO, firmware);
        otaJobMapper.insert(job);

        List<String> deviceKeys = resolveDeviceKeys(otaJobDTO);
        assignDevicesToBatches(job, deviceKeys);

        otaJobCreateCounter.increment();
        log.info("OTA job created: {}, devices: {}", job.getJobId(), deviceKeys.size());

        return convertToOtaJobDTO(job);
    }

    private OtaJob buildOtaJob(OtaJobDTO source, Firmware firmware) {
        OtaJob job = new OtaJob();
        BeanUtils.copyProperties(source, job);
        job.setJobId("ota_" + IdUtil.getSnowflakeNextIdStr());
        job.setTargetVersion(firmware.getVersion());
        job.setStatus(OtaJob.Status.PENDING);
        job.setCurrentBatch(0);
        job.setTotalBatches(source.getTotalBatches() != null ? source.getTotalBatches() : defaultGrayScaleBatches);
        job.setAutoRollbackEnabled(source.getAutoRollbackEnabled() != null ?
                source.getAutoRollbackEnabled() : (defaultRollbackEnabled ? 1 : 0));
        job.setSuccessRateThreshold(source.getSuccessRateThreshold() != null ?
                source.getSuccessRateThreshold() : DEFAULT_SUCCESS_RATE_THRESHOLD);
        job.setUpgradeType(source.getUpgradeType() != null ? source.getUpgradeType() : OtaJob.UpgradeType.FULL);
        job.setGrayScaleStrategy(source.getGrayScaleStrategy() != null ?
                source.getGrayScaleStrategy() : OtaJob.GrayScaleStrategy.BATCH);
        return job;
    }

    private List<String> resolveDeviceKeys(OtaJobDTO otaJobDTO) {
        List<String> deviceKeys = otaJobDTO.getDeviceKeys();
        if (deviceKeys == null || deviceKeys.isEmpty()) {
            return deviceMapper.selectByProductKey(otaJobDTO.getProductKey())
                    .stream()
                    .map(Device::getDeviceKey)
                    .toList();
        }
        return deviceKeys;
    }

    private void assignDevicesToBatches(OtaJob job, List<String> deviceKeys) {
        int batchSize = (int) Math.ceil((double) deviceKeys.size() / job.getTotalBatches());
        Map<String, Device> deviceCache = loadDeviceCache(deviceKeys);

        for (int i = 0; i < deviceKeys.size(); i++) {
            String deviceKey = deviceKeys.get(i);
            Device device = deviceCache.get(deviceKey);
            if (device != null) {
                createDeviceUpgrade(job, device, (i / batchSize) + 1);
            }
        }
    }

    private Map<String, Device> loadDeviceCache(List<String> deviceKeys) {
        Map<String, Device> cache = new HashMap<>((int) (deviceKeys.size() / 0.75f) + 1);
        for (String deviceKey : deviceKeys) {
            Device device = deviceMapper.selectByDeviceKey(deviceKey);
            if (device != null) {
                cache.put(deviceKey, device);
            }
        }
        return cache;
    }

    private void createDeviceUpgrade(OtaJob job, Device device, int batchNumber) {
        OtaDeviceUpgrade upgrade = new OtaDeviceUpgrade();
        upgrade.setJobId(job.getJobId());
        upgrade.setDeviceKey(device.getDeviceKey());
        upgrade.setBatchNumber(batchNumber);
        upgrade.setCurrentVersion(device.getFirmwareVersion());
        upgrade.setTargetVersion(job.getTargetVersion());
        upgrade.setStatus(OtaDeviceUpgrade.Status.PENDING);
        upgrade.setProgress(0);
        upgrade.setRetryCount(0);
        deviceUpgradeMapper.insert(upgrade);
    }

    @Override
    public OtaJobDTO getOtaJob(String jobId) {
        return convertToOtaJobDTO(getOtaJobEntity(jobId));
    }

    @Override
    public IPage<OtaJobDTO> listOtaJobs(Page<OtaJob> page, String productKey, String status) {
        LambdaQueryWrapper<OtaJob> wrapper = buildOtaJobQueryWrapper(productKey, status);
        return otaJobMapper.selectPage(page, wrapper).convert(this::convertToOtaJobDTO);
    }

    private LambdaQueryWrapper<OtaJob> buildOtaJobQueryWrapper(String productKey, String status) {
        LambdaQueryWrapper<OtaJob> wrapper = new LambdaQueryWrapper<>();
        if (productKey != null) {
            wrapper.eq(OtaJob::getProductKey, productKey);
        }
        if (status != null) {
            wrapper.eq(OtaJob::getStatus, status);
        }
        wrapper.orderByDesc(OtaJob::getCreatedAt);
        return wrapper;
    }

    @Override
    @Transactional
    public OtaJobDTO startOtaJob(String jobId) {
        OtaJob job = getOtaJobEntity(jobId);
        if (!OtaJob.Status.PENDING.equals(job.getStatus())) {
            throw BusinessException.badRequest("Only pending jobs can be started");
        }

        job.setStatus(OtaJob.Status.RUNNING);
        job.setStartedAt(LocalDateTime.now());
        job.setCurrentBatch(1);
        otaJobMapper.updateById(job);

        startBatch(job, 1);

        otaJobStartCounter.increment();
        log.info("OTA job started: {}", jobId);

        return convertToOtaJobDTO(job);
    }

    private void startBatch(OtaJob job, int batchNumber) {
        List<OtaDeviceUpgrade> batchDevices = deviceUpgradeMapper.selectByJobIdAndBatch(job.getJobId(), batchNumber);
        LocalDateTime now = LocalDateTime.now();

        for (OtaDeviceUpgrade upgrade : batchDevices) {
            upgrade.setStatus(OtaDeviceUpgrade.Status.DOWNLOADING);
            upgrade.setDownloadStartedAt(now);
            upgrade.setProgress(0);
            deviceUpgradeMapper.updateUpgradeStatus(
                    upgrade.getId(), upgrade.getStatus(), 0, null, null, upgrade.getRetryCount());
        }

        log.info("OTA job {} batch {} started, devices: {}", job.getJobId(), batchNumber, batchDevices.size());
    }

    @Override
    @Transactional
    public OtaJobDTO pauseOtaJob(String jobId) {
        OtaJob job = getOtaJobEntity(jobId);
        if (!OtaJob.Status.RUNNING.equals(job.getStatus())) {
            throw BusinessException.badRequest("Only running jobs can be paused");
        }

        job.setStatus(OtaJob.Status.PAUSED);
        otaJobMapper.updateById(job);

        log.info("OTA job paused: {}", jobId);
        return convertToOtaJobDTO(job);
    }

    @Override
    @Transactional
    public OtaJobDTO cancelOtaJob(String jobId) {
        OtaJob job = getOtaJobEntity(jobId);
        if (COMPLETED_JOB_STATUSES.contains(job.getStatus())) {
            throw BusinessException.badRequest("Completed jobs cannot be cancelled");
        }

        job.setStatus(OtaJob.Status.FAILED);
        job.setCompletedAt(LocalDateTime.now());
        otaJobMapper.updateById(job);

        log.info("OTA job cancelled: {}", jobId);
        return convertToOtaJobDTO(job);
    }

    @Override
    @Transactional
    public OtaJobDTO rollbackOtaJob(String jobId) {
        OtaJob job = getOtaJobEntity(jobId);
        String rollbackFirmwareId = job.getRollbackFirmwareId();
        if (rollbackFirmwareId == null || rollbackFirmwareId.isEmpty()) {
            throw BusinessException.badRequest("No rollback firmware configured for this job");
        }

        Firmware rollbackFirmware = getFirmwareEntity(rollbackFirmwareId);
        markJobRolledBack(job);
        initiateDeviceRollbacks(job, rollbackFirmware);

        otaJobRollbackCounter.increment();
        log.info("OTA job rollback initiated: {}", jobId);

        return convertToOtaJobDTO(job);
    }

    private void markJobRolledBack(OtaJob job) {
        job.setStatus(OtaJob.Status.ROLLEDBACK);
        job.setCompletedAt(LocalDateTime.now());
        otaJobMapper.updateById(job);
    }

    private void initiateDeviceRollbacks(OtaJob job, Firmware rollbackFirmware) {
        List<OtaDeviceUpgrade> upgrades = deviceUpgradeMapper.selectByJobId(job.getJobId());
        for (OtaDeviceUpgrade upgrade : upgrades) {
            if (isUpgradeCompleted(upgrade)) {
                upgrade.setStatus(OtaDeviceUpgrade.Status.ROLLBACK);
                upgrade.setTargetVersion(rollbackFirmware.getVersion());
                upgrade.setProgress(0);
                deviceUpgradeMapper.updateById(upgrade);
                log.info("Device {} rollback initiated: {} -> {}",
                        upgrade.getDeviceKey(), upgrade.getCurrentVersion(), rollbackFirmware.getVersion());
            }
        }
    }

    private boolean isUpgradeCompleted(OtaDeviceUpgrade upgrade) {
        String status = upgrade.getStatus();
        return OtaDeviceUpgrade.Status.SUCCESS.equals(status) || OtaDeviceUpgrade.Status.FAILED.equals(status);
    }

    @Override
    @Scheduled(fixedDelay = 60000)
    @Transactional
    public void processGrayScaleBatches() {
        List<OtaJob> runningJobs = otaJobMapper.selectByStatus(OtaJob.Status.RUNNING);
        for (OtaJob job : runningJobs) {
            processJobBatchSafely(job);
        }
    }

    private void processJobBatchSafely(OtaJob job) {
        ReentrantLock lock = jobLocks.computeIfAbsent(job.getJobId(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.debug("Job {} is already being processed, skipping", job.getJobId());
            return;
        }
        try {
            checkAndProcessBatch(job);
        } catch (Exception e) {
            log.error("Error processing OTA job: {}", job.getJobId(), e);
        } finally {
            lock.unlock();
        }
    }

    private void checkAndProcessBatch(OtaJob job) {
        int currentBatch = job.getCurrentBatch();
        List<OtaDeviceUpgrade> batchDevices = deviceUpgradeMapper.selectByJobIdAndBatch(job.getJobId(), currentBatch);

        if (batchDevices.isEmpty()) {
            advanceToNextBatch(job);
            return;
        }

        BatchStatistics stats = calculateBatchStatistics(batchDevices);
        if (stats.completedCount() < batchDevices.size()) {
            return;
        }

        BigDecimal successRate = calculateSuccessRate(stats.successCount(), batchDevices.size());
        boolean batchSuccess = successRate.compareTo(job.getSuccessRateThreshold()) >= 0;

        if (!batchSuccess && job.getAutoRollbackEnabled() == 1) {
            log.warn("Batch {} failed, success rate: {}%, initiating rollback", currentBatch, successRate);
            rollbackOtaJob(job.getJobId());
            return;
        }

        logBatchResult(currentBatch, successRate, batchSuccess, job.getAutoRollbackEnabled());
        advanceToNextBatch(job);
    }

    private BatchStatistics calculateBatchStatistics(List<OtaDeviceUpgrade> devices) {
        int successCount = 0;
        int failedCount = 0;

        for (OtaDeviceUpgrade device : devices) {
            String status = device.getStatus();
            if (OtaDeviceUpgrade.Status.SUCCESS.equals(status)) {
                successCount++;
            } else if (OtaDeviceUpgrade.Status.FAILED.equals(status)) {
                failedCount++;
            }
        }

        return new BatchStatistics(successCount, failedCount, successCount + failedCount);
    }

    private record BatchStatistics(int successCount, int failedCount, int completedCount) {}

    private BigDecimal calculateSuccessRate(long successCount, int total) {
        if (total == 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(successCount)
                .multiply(ONE_HUNDRED)
                .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
    }

    private void logBatchResult(int batchNumber, BigDecimal successRate, boolean success, int autoRollbackEnabled) {
        if (success) {
            log.info("Batch {} completed successfully, success rate: {}%", batchNumber, successRate);
        } else if (autoRollbackEnabled != 1) {
            log.warn("Batch {} completed with low success rate: {}%, but rollback disabled", batchNumber, successRate);
        }
    }

    private void advanceToNextBatch(OtaJob job) {
        if (job.getCurrentBatch() >= job.getTotalBatches()) {
            completeJobSuccessfully(job);
        } else {
            startNextBatch(job);
        }
    }

    private void completeJobSuccessfully(OtaJob job) {
        job.setStatus(OtaJob.Status.SUCCESS);
        job.setCompletedAt(LocalDateTime.now());
        otaJobMapper.updateById(job);
        jobLocks.remove(job.getJobId());
        otaJobSuccessCounter.increment();
        log.info("OTA job completed successfully: {}", job.getJobId());
    }

    private void startNextBatch(OtaJob job) {
        int nextBatch = job.getCurrentBatch() + 1;
        job.setCurrentBatch(nextBatch);
        otaJobMapper.updateById(job);
        startBatch(job, nextBatch);
    }

    @Override
    @Transactional
    public OtaDeviceUpgrade updateDeviceUpgradeProgress(String jobId, String deviceKey,
                                                        String status, Integer progress,
                                                        String errorCode, String errorMessage) {
        OtaDeviceUpgrade upgrade = getDeviceUpgradeEntity(jobId, deviceKey);

        handleUpgradeStatusChange(upgrade, status, errorCode, errorMessage);

        if (isFinalStatus(status)) {
            completeUpgrade(upgrade, status);
        } else {
            updateUpgradeProgress(upgrade, status, progress, errorCode, errorMessage);
        }

        log.debug("Device {} upgrade progress updated: {} - {}%", deviceKey, status, progress);
        return upgrade;
    }

    private OtaDeviceUpgrade getDeviceUpgradeEntity(String jobId, String deviceKey) {
        OtaDeviceUpgrade upgrade = deviceUpgradeMapper.selectByJobIdAndDeviceKey(jobId, deviceKey);
        if (upgrade == null) {
            throw BusinessException.notFound("Upgrade record not found for device: " + deviceKey);
        }
        return upgrade;
    }

    private void handleUpgradeStatusChange(OtaDeviceUpgrade upgrade, String status,
                                           String errorCode, String errorMessage) {
        if (OtaDeviceUpgrade.Status.UPGRADING.equals(status)) {
            upgrade.setUpgradeStartedAt(LocalDateTime.now());
        }
        upgrade.setStatus(status);
        upgrade.setErrorCode(errorCode);
        upgrade.setErrorMessage(errorMessage);
    }

    private boolean isFinalStatus(String status) {
        return OtaDeviceUpgrade.Status.SUCCESS.equals(status) || OtaDeviceUpgrade.Status.FAILED.equals(status);
    }

    private void completeUpgrade(OtaDeviceUpgrade upgrade, String status) {
        upgrade.setCompletedAt(LocalDateTime.now());
        deviceUpgradeMapper.completeUpgrade(upgrade.getId(), status, LocalDateTime.now());

        if (OtaDeviceUpgrade.Status.SUCCESS.equals(status)) {
            updateDeviceFirmwareVersion(upgrade);
            deviceUpgradeSuccessCounter.increment();
        } else {
            deviceUpgradeFailedCounter.increment();
        }
    }

    private void updateDeviceFirmwareVersion(OtaDeviceUpgrade upgrade) {
        Device device = deviceMapper.selectByDeviceKey(upgrade.getDeviceKey());
        if (device != null) {
            device.setFirmwareVersion(upgrade.getTargetVersion());
            deviceMapper.updateById(device);
        }
    }

    private void updateUpgradeProgress(OtaDeviceUpgrade upgrade, String status, Integer progress,
                                       String errorCode, String errorMessage) {
        upgrade.setProgress(progress);
        deviceUpgradeMapper.updateUpgradeStatus(
                upgrade.getId(), status, progress, errorCode, errorMessage, upgrade.getRetryCount());
    }

    @Override
    public List<OtaDeviceUpgrade> getDeviceUpgrades(String jobId) {
        return deviceUpgradeMapper.selectByJobId(jobId);
    }

    @Override
    public OtaDeviceUpgrade getDeviceUpgrade(String jobId, String deviceKey) {
        return getDeviceUpgradeEntity(jobId, deviceKey);
    }

    @Override
    public Map<String, Object> getOtaJobStatistics(String jobId) {
        OtaJob job = getOtaJobEntity(jobId);
        List<OtaDeviceUpgrade> allUpgrades = deviceUpgradeMapper.selectByJobId(jobId);
        JobStatistics stats = calculateJobStatistics(allUpgrades);

        BigDecimal successRate = calculateSuccessRate(stats.success(), allUpgrades.size());

        Map<String, Object> result = new HashMap<>(16);
        result.put("jobId", jobId);
        result.put("status", job.getStatus());
        result.put("currentBatch", job.getCurrentBatch());
        result.put("totalBatches", job.getTotalBatches());
        result.put("totalDevices", allUpgrades.size());
        result.put("pending", stats.pending());
        result.put("downloading", stats.downloading());
        result.put("upgrading", stats.upgrading());
        result.put("success", stats.success());
        result.put("failed", stats.failed());
        result.put("successRate", successRate);
        result.put("threshold", job.getSuccessRateThreshold());
        return result;
    }

    private JobStatistics calculateJobStatistics(List<OtaDeviceUpgrade> upgrades) {
        int pending = 0;
        int downloading = 0;
        int upgrading = 0;
        int success = 0;
        int failed = 0;

        for (OtaDeviceUpgrade upgrade : upgrades) {
            switch (upgrade.getStatus()) {
                case OtaDeviceUpgrade.Status.PENDING -> pending++;
                case OtaDeviceUpgrade.Status.DOWNLOADING -> downloading++;
                case OtaDeviceUpgrade.Status.UPGRADING -> upgrading++;
                case OtaDeviceUpgrade.Status.SUCCESS -> success++;
                case OtaDeviceUpgrade.Status.FAILED -> failed++;
                default -> {
                }
            }
        }

        return new JobStatistics(pending, downloading, upgrading, success, failed);
    }

    private record JobStatistics(int pending, int downloading, int upgrading, int success, int failed) {}

    private long calculateDiffSize(Long fromSize, Long toSize, String algorithm) {
        long from = fromSize != null ? fromSize : 0L;
        long to = toSize != null ? toSize : 0L;
        long baseSize = Math.max(Math.abs(to - from), 1024L);

        return switch (algorithm) {
            case "bsdiff" -> (long) (baseSize * 0.7);
            case "hdiff" -> (long) (baseSize * 0.5);
            case "delta" -> (long) (baseSize * 0.6);
            default -> baseSize;
        };
    }

    private double calculateCompressionRatio(Long fromSize, Long toSize, Long diffSize) {
        if (toSize == null || toSize == 0 || diffSize == null) {
            return 0.0;
        }
        return Math.round((1.0 - (double) diffSize / toSize) * 100.0) / 100.0;
    }

    private Firmware getFirmwareEntity(String firmwareId) {
        Firmware firmware = firmwareMapper.selectByFirmwareId(firmwareId);
        if (firmware == null) {
            throw BusinessException.notFound("Firmware not found: " + firmwareId);
        }
        return firmware;
    }

    private OtaJob getOtaJobEntity(String jobId) {
        OtaJob job = otaJobMapper.selectByJobId(jobId);
        if (job == null) {
            throw BusinessException.notFound("OTA job not found: " + jobId);
        }
        return job;
    }

    private FirmwareDTO convertToFirmwareDTO(Firmware firmware) {
        FirmwareDTO dto = new FirmwareDTO();
        BeanUtils.copyProperties(firmware, dto);
        return dto;
    }

    private OtaJobDTO convertToOtaJobDTO(OtaJob job) {
        OtaJobDTO dto = new OtaJobDTO();
        BeanUtils.copyProperties(job, dto);
        return dto;
    }
}
