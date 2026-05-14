package com.configcenter.version.service;

import cn.hutool.crypto.SecureUtil;
import com.alibaba.fastjson.JSON;
import com.configcenter.common.entity.ConfigVersion;
import com.configcenter.common.exception.BusinessException;
import com.configcenter.common.util.VersionUtils;
import com.configcenter.version.config.VersionCompressionProperties;
import com.configcenter.version.entity.VersionCompressionArchive;
import com.configcenter.version.repository.ConfigVersionRepository;
import com.configcenter.version.repository.VersionCompressionArchiveRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class VersionCompressionService {

    private final ConfigVersionRepository configVersionRepository;
    private final VersionCompressionArchiveRepository archiveRepository;
    private final VersionCompressionProperties properties;

    @Transactional
    public Map<String, Object> compressVersions(String configId, String operator) {
        log.info("Starting version compression: configId={}", configId);
        
        if (!properties.getEnabled()) {
            log.warn("Version compression is disabled");
            Map<String, Object> result = new HashMap<>();
            result.put("compressed", false);
            result.put("reason", "compression disabled");
            return result;
        }

        int threshold = properties.getThresholdForConfig(configId);
        int keepVersions = properties.getKeepVersionsForConfig(configId);
        
        log.info("Using policy for configId={}: threshold={}, keepVersions={}", 
                configId, threshold, keepVersions);

        List<ConfigVersion> allVersions = configVersionRepository.findByConfigIdOrderByChangedAtDesc(configId);
        log.info("Total versions found: {}", allVersions.size());

        TriggerCheckResult triggerCheck = shouldTriggerCompression(allVersions, threshold);
        
        if (!triggerCheck.shouldCompress) {
            log.info("Compression not triggered: reason={}, currentCount={}, threshold={}", 
                    triggerCheck.reason, allVersions.size(), threshold);
            Map<String, Object> result = new HashMap<>();
            result.put("compressed", false);
            result.put("reason", triggerCheck.reason);
            result.put("currentCount", allVersions.size());
            result.put("threshold", threshold);
            return result;
        }

        RetentionResult retentionResult = determineVersionsToCompress(allVersions, keepVersions, configId);
        List<ConfigVersion> versionsToCompressList = retentionResult.versionsToCompress;

        if (versionsToCompressList.isEmpty()) {
            Map<String, Object> result = new HashMap<>();
            result.put("compressed", false);
            result.put("reason", "no versions to compress per retention policy");
            return result;
        }

        String fromVersion = versionsToCompressList.get(0).getVersion();
        String toVersion = versionsToCompressList.get(versionsToCompressList.size() - 1).getVersion();

        String jsonData = JSON.toJSONString(versionsToCompressList);
        byte[] originalBytes = jsonData.getBytes(StandardCharsets.UTF_8);
        long originalSize = originalBytes.length;

        int minSize = getMinCompressionSize(configId);
        if (originalSize < minSize) {
            log.info("Data size {} below minimum compression size {}, skipping", 
                    originalSize, minSize);
            Map<String, Object> result = new HashMap<>();
            result.put("compressed", false);
            result.put("reason", "below minimum size");
            return result;
        }

        byte[] compressedData = compress(originalBytes);
        long compressedSize = compressedData.length;
        double compressionRatio = (double) compressedSize / originalSize;

        log.info("Compression completed: original={} bytes, compressed={} bytes, ratio={}", 
                originalSize, compressedSize, String.format("%.2f", compressionRatio));

        String checksum = SecureUtil.sha256(jsonData);

        String algorithm = getCompressionAlgorithm(configId);
        VersionCompressionArchive archive = VersionCompressionArchive.builder()
                .configId(configId)
                .fromVersion(fromVersion)
                .toVersion(toVersion)
                .versionCount(versionsToCompressList.size())
                .compressedData(compressedData)
                .compressionAlgorithm(algorithm)
                .originalSize(originalSize)
                .compressedSize(compressedSize)
                .compressionRatio(compressionRatio)
                .archiveTime(LocalDateTime.now())
                .archivedBy(operator)
                .checksum(checksum)
                .build();

        archiveRepository.save(archive);

        for (ConfigVersion version : versionsToCompressList) {
            configVersionRepository.delete(version);
        }

        log.info("Version compression completed: configId={}, archived={} versions, from={}, to={}", 
                configId, versionsToCompressList.size(), fromVersion, toVersion);

        Map<String, Object> result = new HashMap<>();
        result.put("compressed", true);
        result.put("archiveId", archive.getArchiveId());
        result.put("versionsCompressed", versionsToCompressList.size());
        result.put("fromVersion", fromVersion);
        result.put("toVersion", toVersion);
        result.put("originalSize", originalSize);
        result.put("compressedSize", compressedSize);
        result.put("compressionRatio", compressionRatio);
        result.put("spaceSaved", originalSize - compressedSize);
        result.put("remainingVersions", allVersions.size() - versionsToCompressList.size());
        result.put("thresholdUsed", threshold);
        result.put("keepVersionsUsed", keepVersions);
        result.put("triggerReason", triggerCheck.reason);
        result.put("retentionCriticalKept", retentionResult.criticalKept);

        return result;
    }

    private int getMinCompressionSize(String configId) {
        if (configId != null && properties.getConfigPolicies() != null) {
            for (VersionCompressionProperties.ConfigCompressionPolicy policy : properties.getConfigPolicies()) {
                if (policy.getConfigIdPattern() != null && configId.matches(policy.getConfigIdPattern())) {
                    if (policy.getMinCompressionSize() != null) {
                        return policy.getMinCompressionSize();
                    }
                }
            }
        }
        return properties.getMinCompressionSize();
    }

    private String getCompressionAlgorithm(String configId) {
        if (configId != null && properties.getConfigPolicies() != null) {
            for (VersionCompressionProperties.ConfigCompressionPolicy policy : properties.getConfigPolicies()) {
                if (policy.getConfigIdPattern() != null && configId.matches(policy.getConfigIdPattern())) {
                    if (policy.getCompressionAlgorithm() != null) {
                        return policy.getCompressionAlgorithm();
                    }
                }
            }
        }
        return properties.getCompressionAlgorithm();
    }

    private static class TriggerCheckResult {
        boolean shouldCompress;
        String reason;
    }

    private TriggerCheckResult shouldTriggerCompression(List<ConfigVersion> allVersions, int threshold) {
        TriggerCheckResult result = new TriggerCheckResult();
        result.shouldCompress = false;
        result.reason = "no trigger condition met";

        VersionCompressionProperties.CompressionTriggerConfig trigger = properties.getTrigger();
        
        String mode = trigger.getMode();
        if (mode == null) {
            mode = "VERSION_COUNT";
        }

        switch (mode) {
            case "VERSION_COUNT":
                if (allVersions.size() > threshold) {
                    result.shouldCompress = true;
                    result.reason = "version_count_threshold_exceeded";
                } else {
                    result.reason = "version_count_below_threshold";
                }
                break;

            case "TIME_INTERVAL":
                if (allVersions.size() >= trigger.getMinVersionsForInterval()) {
                    LocalDateTime oldestVersionTime = allVersions.get(allVersions.size() - 1).getChangedAt();
                    long hoursSinceOldest = ChronoUnit.HOURS.between(oldestVersionTime, LocalDateTime.now());
                    
                    if (hoursSinceOldest >= trigger.getTimeIntervalHours()) {
                        result.shouldCompress = true;
                        result.reason = "time_interval_reached";
                    } else {
                        result.reason = "time_interval_not_reached";
                    }
                } else {
                    result.reason = "insufficient_versions_for_time_check";
                }
                break;

            case "BOTH_ANY":
                boolean countTrigger = allVersions.size() > threshold;
                boolean timeTrigger = false;
                
                if (allVersions.size() >= trigger.getMinVersionsForInterval()) {
                    LocalDateTime oldestVersionTime = allVersions.get(allVersions.size() - 1).getChangedAt();
                    long hoursSinceOldest = ChronoUnit.HOURS.between(oldestVersionTime, LocalDateTime.now());
                    timeTrigger = hoursSinceOldest >= trigger.getTimeIntervalHours();
                }
                
                if (countTrigger || timeTrigger) {
                    result.shouldCompress = true;
                    result.reason = countTrigger ? "version_count_threshold_exceeded" : "time_interval_reached";
                } else {
                    result.reason = "neither_trigger_met";
                }
                break;

            case "BOTH_ALL":
                if (allVersions.size() > threshold) {
                    if (allVersions.size() >= trigger.getMinVersionsForInterval()) {
                        LocalDateTime oldestVersionTime = allVersions.get(allVersions.size() - 1).getChangedAt();
                        long hoursSinceOldest = ChronoUnit.HOURS.between(oldestVersionTime, LocalDateTime.now());
                        
                        if (hoursSinceOldest >= trigger.getTimeIntervalHours()) {
                            result.shouldCompress = true;
                            result.reason = "both_triggers_met";
                        } else {
                            result.reason = "time_interval_not_reached";
                        }
                    } else {
                        result.reason = "insufficient_versions_for_time_check";
                    }
                } else {
                    result.reason = "version_count_below_threshold";
                }
                break;

            default:
                if (allVersions.size() > threshold) {
                    result.shouldCompress = true;
                    result.reason = "version_count_threshold_exceeded";
                }
        }

        return result;
    }

    private static class RetentionResult {
        List<ConfigVersion> versionsToCompress;
        int criticalKept;
    }

    private RetentionResult determineVersionsToCompress(List<ConfigVersion> allVersions, 
                                                         int keepVersions, String configId) {
        RetentionResult result = new RetentionResult();
        result.versionsToCompress = new ArrayList<>();
        result.criticalKept = 0;

        VersionCompressionProperties.RetentionPolicyConfig retention = properties.getRetention();
        String mode = retention.getMode();
        if (mode == null) {
            mode = "KEEP_LATEST";
        }

        switch (mode) {
            case "KEEP_LATEST":
                if (allVersions.size() > keepVersions) {
                    result.versionsToCompress = new ArrayList<>(allVersions.subList(keepVersions, allVersions.size()));
                }
                break;

            case "BY_DATE":
                LocalDateTime cutoffDate = LocalDateTime.now().minusDays(retention.getRetentionDays());
                for (ConfigVersion version : allVersions) {
                    if (version.getChangedAt().isBefore(cutoffDate)) {
                        if (!isCriticalVersion(version, retention)) {
                            result.versionsToCompress.add(version);
                        } else {
                            result.criticalKept++;
                        }
                    }
                }
                break;

            case "MIXED":
                List<ConfigVersion> candidates = new ArrayList<>();
                
                if (allVersions.size() > keepVersions) {
                    candidates = new ArrayList<>(allVersions.subList(keepVersions, allVersions.size()));
                }
                
                LocalDateTime dateCutoff = LocalDateTime.now().minusDays(retention.getRetentionDays());
                List<ConfigVersion> toCompress = new ArrayList<>();
                
                for (ConfigVersion version : candidates) {
                    if (version.getChangedAt().isBefore(dateCutoff)) {
                        if (!isCriticalVersion(version, retention)) {
                            toCompress.add(version);
                        } else {
                            result.criticalKept++;
                        }
                    }
                }
                
                if (allVersions.size() > retention.getMaxTotalVersions()) {
                    int excess = allVersions.size() - retention.getMaxTotalVersions();
                    int additionalToCompress = Math.max(0, excess - toCompress.size());
                    
                    for (ConfigVersion version : candidates) {
                        if (additionalToCompress <= 0) break;
                        if (!toCompress.contains(version) && !isCriticalVersion(version, retention)) {
                            toCompress.add(version);
                            additionalToCompress--;
                        }
                    }
                }
                
                result.versionsToCompress = toCompress;
                break;

            default:
                if (allVersions.size() > keepVersions) {
                    result.versionsToCompress = new ArrayList<>(allVersions.subList(keepVersions, allVersions.size()));
                }
        }

        return result;
    }

    private boolean isCriticalVersion(ConfigVersion version, 
                                       VersionCompressionProperties.RetentionPolicyConfig retention) {
        if (!Boolean.TRUE.equals(retention.getKeepCriticalVersions())) {
            return false;
        }

        List<String> keywords = retention.getCriticalVersionKeywords();
        if (keywords == null || keywords.isEmpty()) {
            keywords = Arrays.asList("release", "production", "critical", "major", "rollback", "important");
        }

        String description = version.getChangeReason() != null ? 
                version.getChangeReason().toLowerCase() : "";
        String versionName = version.getVersion() != null ? 
                version.getVersion().toLowerCase() : "";

        for (String keyword : keywords) {
            if (description.contains(keyword.toLowerCase()) || versionName.contains(keyword.toLowerCase())) {
                log.info("Keeping critical version: version={}, reason={}", 
                        version.getVersion(), version.getChangeReason());
                return true;
            }
        }

        return false;
    }

    @Transactional(readOnly = true)
    public List<ConfigVersion> restoreVersions(String configId, String archiveId) {
        log.info("Restoring versions from archive: configId={}, archiveId={}", configId, archiveId);

        VersionCompressionArchive archive = archiveRepository.findById(archiveId)
                .orElseThrow(() -> new BusinessException("归档不存在: " + archiveId));

        if (!archive.getConfigId().equals(configId)) {
            throw new BusinessException("归档不属于指定配置");
        }

        byte[] decompressedData = decompress(archive.getCompressedData());
        String jsonData = new String(decompressedData, StandardCharsets.UTF_8);

        String checksum = SecureUtil.sha256(jsonData);
        if (!checksum.equals(archive.getChecksum())) {
            throw new BusinessException("数据校验失败，归档可能已被篡改");
        }

        List<ConfigVersion> restoredVersions = JSON.parseArray(jsonData, ConfigVersion.class);
        log.info("Restored {} versions from archive", restoredVersions.size());

        return restoredVersions;
    }

    @Transactional
    public List<ConfigVersion> restoreAndSaveVersions(String configId, String archiveId) {
        List<ConfigVersion> restoredVersions = restoreVersions(configId, archiveId);
        
        for (ConfigVersion version : restoredVersions) {
            if (!configVersionRepository.existsByConfigIdAndVersion(configId, version.getVersion())) {
                version.setVersionId(null);
                configVersionRepository.save(version);
            }
        }

        return restoredVersions;
    }

    public List<ConfigVersion> getVersionHistoryWithCompressed(String configId) {
        List<ConfigVersion> activeVersions = configVersionRepository.findByConfigIdOrderByChangedAtDesc(configId);
        List<ConfigVersion> allVersions = new ArrayList<>(activeVersions);

        List<VersionCompressionArchive> archives = archiveRepository.findByConfigIdOrderByArchiveTimeDesc(configId);
        for (VersionCompressionArchive archive : archives) {
            try {
                List<ConfigVersion> compressedVersions = restoreVersions(configId, archive.getArchiveId());
                allVersions.addAll(compressedVersions);
            } catch (Exception e) {
                log.error("Failed to restore versions from archive: {}", archive.getArchiveId(), e);
            }
        }

        allVersions.sort((v1, v2) -> VersionUtils.compareVersion(v2.getVersion(), v1.getVersion()));
        return allVersions;
    }

    public Map<String, Object> getCompressionStatistics(String configId) {
        List<ConfigVersion> activeVersions = configVersionRepository.findByConfigIdOrderByChangedAtDesc(configId);
        List<VersionCompressionArchive> archives = archiveRepository.findByConfigIdOrderByArchiveTimeDesc(configId);

        long totalOriginalSize = 0;
        long totalCompressedSize = 0;
        int totalArchivedVersions = 0;

        for (VersionCompressionArchive archive : archives) {
            totalOriginalSize += archive.getOriginalSize();
            totalCompressedSize += archive.getCompressedSize();
            totalArchivedVersions += archive.getVersionCount();
        }

        Map<String, Object> stats = new HashMap<>();
        stats.put("activeVersions", activeVersions.size());
        stats.put("archivedVersions", totalArchivedVersions);
        stats.put("totalVersions", activeVersions.size() + totalArchivedVersions);
        stats.put("archiveCount", archives.size());
        stats.put("totalOriginalSize", totalOriginalSize);
        stats.put("totalCompressedSize", totalCompressedSize);
        stats.put("totalSpaceSaved", totalOriginalSize - totalCompressedSize);
        
        if (totalOriginalSize > 0) {
            stats.put("overallCompressionRatio", (double) totalCompressedSize / totalOriginalSize);
        } else {
            stats.put("overallCompressionRatio", 0.0);
        }

        stats.put("currentThreshold", properties.getThresholdForConfig(configId));
        stats.put("currentKeepVersions", properties.getKeepVersionsForConfig(configId));
        stats.put("triggerMode", properties.getTrigger().getMode());
        stats.put("retentionMode", properties.getRetention().getMode());
        stats.put("minCompressionSize", getMinCompressionSize(configId));
        stats.put("compressionAlgorithm", getCompressionAlgorithm(configId));

        return stats;
    }

    public List<VersionCompressionArchive> getArchives(String configId) {
        return archiveRepository.findByConfigIdOrderByArchiveTimeDesc(configId);
    }

    public VersionCompressionArchive getArchive(String archiveId) {
        return archiveRepository.findById(archiveId)
                .orElseThrow(() -> new BusinessException("归档不存在: " + archiveId));
    }

    private byte[] compress(byte[] data) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(data);
            gzipOut.finish();
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Compression failed", e);
            throw new BusinessException("压缩失败: " + e.getMessage(), e);
        }
    }

    private byte[] decompress(byte[] compressedData) {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(compressedData);
             GZIPInputStream gzipIn = new GZIPInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            
            byte[] buffer = new byte[1024];
            int len;
            while ((len = gzipIn.read(buffer)) > 0) {
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Decompression failed", e);
            throw new BusinessException("解压失败: " + e.getMessage(), e);
        }
    }

    public boolean isCompressionEligible(String configId) {
        if (!properties.getEnabled()) {
            return false;
        }
        List<ConfigVersion> versions = configVersionRepository.findByConfigIdOrderByChangedAtDesc(configId);
        int threshold = properties.getThresholdForConfig(configId);
        
        TriggerCheckResult check = shouldTriggerCompression(versions, threshold);
        return check.shouldCompress;
    }

    @Transactional
    public void deleteArchive(String archiveId) {
        log.info("Deleting archive: {}", archiveId);
        archiveRepository.deleteById(archiveId);
    }

    public Map<String, Object> getCompressionPolicyInfo(String configId) {
        Map<String, Object> info = new HashMap<>();
        info.put("configId", configId);
        info.put("globalEnabled", properties.getEnabled());
        info.put("threshold", properties.getThresholdForConfig(configId));
        info.put("keepVersions", properties.getKeepVersionsForConfig(configId));
        info.put("minCompressionSize", getMinCompressionSize(configId));
        info.put("compressionAlgorithm", getCompressionAlgorithm(configId));
        
        VersionCompressionProperties.CompressionTriggerConfig trigger = properties.getTrigger();
        Map<String, Object> triggerInfo = new HashMap<>();
        triggerInfo.put("mode", trigger.getMode());
        triggerInfo.put("versionCountThreshold", trigger.getVersionCountThreshold());
        triggerInfo.put("timeIntervalHours", trigger.getTimeIntervalHours());
        triggerInfo.put("minVersionsForInterval", trigger.getMinVersionsForInterval());
        info.put("trigger", triggerInfo);
        
        VersionCompressionProperties.RetentionPolicyConfig retention = properties.getRetention();
        Map<String, Object> retentionInfo = new HashMap<>();
        retentionInfo.put("mode", retention.getMode());
        retentionInfo.put("keepLatestN", retention.getKeepLatestN());
        retentionInfo.put("retentionDays", retention.getRetentionDays());
        retentionInfo.put("keepCriticalVersions", retention.getKeepCriticalVersions());
        retentionInfo.put("criticalKeywords", retention.getCriticalVersionKeywords());
        retentionInfo.put("maxTotalVersions", retention.getMaxTotalVersions());
        info.put("retention", retentionInfo);
        
        return info;
    }
}
