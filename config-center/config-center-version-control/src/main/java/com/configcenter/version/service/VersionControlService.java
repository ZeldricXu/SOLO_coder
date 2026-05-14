package com.configcenter.version.service;

import com.configcenter.common.dto.*;
import com.configcenter.common.entity.*;
import com.configcenter.common.exception.*;
import com.configcenter.common.util.*;
import com.configcenter.config.repository.ConfigItemRepository;
import com.configcenter.version.repository.ConfigVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class VersionControlService {

    private final ConfigVersionRepository configVersionRepository;
    private final ConfigItemRepository configItemRepository;

    @Transactional
    public VersionDTO createVersion(ConfigItem item, String changeReason, String changedBy, String newValue) {
        String nextVersion = VersionUtils.getNextVersion(item.getCurrentVersion());
        log.info("Creating version: configId={}, currentVersion={}, nextVersion={}", 
                item.getConfigId(), item.getCurrentVersion(), nextVersion);

        ConfigVersion version = new ConfigVersion();
        version.setConfigId(item.getConfigId());
        version.setVersion(nextVersion);
        version.setConfigValue(newValue);
        version.setChangeReason(changeReason);
        version.setChangedBy(changedBy);

        ConfigVersion saved = configVersionRepository.save(version);
        log.info("Version created: versionId={}", saved.getVersionId());
        return EntityConverter.toVersionDTO(saved);
    }

    @Transactional
    public VersionDTO createRollbackVersion(ConfigItem item, String rollbackReason, String changedBy, 
            String targetVersion, String targetValue) {
        String nextVersion = VersionUtils.getNextVersion(item.getCurrentVersion());
        log.info("Creating rollback version: configId={}, targetVersion={}, newVersion={}", 
                item.getConfigId(), targetVersion, nextVersion);

        ConfigVersion version = new ConfigVersion();
        version.setConfigId(item.getConfigId());
        version.setVersion(nextVersion);
        version.setConfigValue(targetValue);
        version.setChangeReason(rollbackReason != null ? rollbackReason : "回滚到版本 " + targetVersion);
        version.setChangedBy(changedBy);
        version.setIsRollback(true);
        version.setRollbackFromVersion(targetVersion);

        ConfigVersion saved = configVersionRepository.save(version);
        log.info("Rollback version created: versionId={}", saved.getVersionId());
        return EntityConverter.toVersionDTO(saved);
    }

    public List<VersionDTO> getVersionHistory(String configId) {
        log.info("Getting version history: configId={}", configId);
        List<ConfigVersion> versions = configVersionRepository.findByConfigIdOrderByChangedAtDesc(configId);
        List<VersionDTO> result = new ArrayList<>();
        for (ConfigVersion v : versions) {
            result.add(EntityConverter.toVersionDTO(v));
        }
        return result;
    }

    public VersionDTO getVersion(String configId, String version) {
        ConfigVersion v = configVersionRepository.findByConfigIdAndVersion(configId, version)
                .orElseThrow(() -> new VersionNotFoundException(configId, version));
        return EntityConverter.toVersionDTO(v);
    }

    public List<VersionDTO> getLatestVersions(String configId, int limit) {
        List<ConfigVersion> versions = configVersionRepository.findLatestVersions(configId, PageRequest.of(0, limit));
        List<VersionDTO> result = new ArrayList<>();
        for (ConfigVersion v : versions) {
            result.add(EntityConverter.toVersionDTO(v));
        }
        return result;
    }

    @Transactional
    public VersionDTO rollback(RollbackConfigRequest request) {
        log.info("Rolling back config: configId={}, targetVersion={}", 
                request.getConfigId(), request.getTargetVersion());

        ConfigItem item = configItemRepository.findByConfigIdAndDeletedFalse(request.getConfigId())
                .orElseThrow(() -> new ConfigNotFoundException(request.getConfigId()));

        ConfigVersion targetVersion = configVersionRepository.findByConfigIdAndVersion(
                request.getConfigId(), request.getTargetVersion())
                .orElseThrow(() -> new VersionNotFoundException(request.getConfigId(), request.getTargetVersion()));

        if (item.getCurrentVersion().equals(request.getTargetVersion())) {
            log.warn("Config already at target version: configId={}, version={}", 
                    request.getConfigId(), request.getTargetVersion());
            throw new BusinessException("当前已是目标版本，无需回滚");
        }

        String oldValue = item.getConfigValue();
        String targetValue = targetVersion.getConfigValue();

        VersionDTO rollbackVersion = createRollbackVersion(item, request.getRollbackReason(), 
                request.getOperator(), request.getTargetVersion(), targetValue);

        item.setConfigValue(targetValue);
        item.setCurrentVersion(rollbackVersion.getVersion());
        item.setUpdatedBy(request.getOperator());
        configItemRepository.save(item);

        log.info("Config rolled back: configId={}, fromVersion={}, toVersion={}", 
                request.getConfigId(), item.getCurrentVersion(), request.getTargetVersion());

        return rollbackVersion;
    }

    public void deleteVersionsByConfigId(String configId) {
        log.info("Deleting versions by configId: {}", configId);
        configVersionRepository.deleteByConfigId(configId);
    }
}
