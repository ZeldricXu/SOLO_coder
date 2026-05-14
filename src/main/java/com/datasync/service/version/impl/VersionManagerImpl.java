package com.datasync.service.version.impl;

import com.datasync.common.Constants;
import com.datasync.model.DataVersion;
import com.datasync.service.version.VersionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class VersionManagerImpl implements VersionManager {

    private static final Logger logger = LoggerFactory.getLogger(VersionManagerImpl.class);

    private final Map<String, DataVersion> versionCache = new ConcurrentHashMap<>();

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private String getVersionKey(String dataSource, String dataKey) {
        return dataSource + ":" + dataKey;
    }

    @Override
    public DataVersion saveVersion(DataVersion version) {
        if (version.getVersionId() == null) {
            version.setVersionId(version.generateVersionId());
        }
        if (version.getUpdatedAt() == null) {
            version.setUpdatedAt(Instant.now());
        }
        String key = getVersionKey(version.getDataSource(), version.getDataKey());
        versionCache.put(key, version);
        saveToRedis(Constants.REDIS_KEY_PREFIX_VERSION + key, version);
        logger.debug("Saved version: {} -> {}", key, version.getVersion());
        return version;
    }

    @Override
    public Optional<DataVersion> getVersion(String dataSource, String dataKey) {
        String key = getVersionKey(dataSource, dataKey);
        DataVersion cached = versionCache.get(key);
        if (cached != null) {
            return Optional.of(cached);
        }
        try {
            String json = redisTemplate.opsForValue().get(Constants.REDIS_KEY_PREFIX_VERSION + key);
            if (json != null) {
                DataVersion version = objectMapper.readValue(json, DataVersion.class);
                versionCache.put(key, version);
                return Optional.of(version);
            }
        } catch (Exception e) {
            logger.warn("Failed to get version from Redis: {}", key, e);
        }
        return Optional.empty();
    }

    @Override
    public List<DataVersion> getVersions(String dataSource) {
        return versionCache.values().stream()
                .filter(v -> dataSource.equals(v.getDataSource()))
                .collect(Collectors.toList());
    }

    @Override
    public boolean deleteVersion(String dataSource, String dataKey) {
        String key = getVersionKey(dataSource, dataKey);
        DataVersion removed = versionCache.remove(key);
        if (removed != null) {
            deleteFromRedis(Constants.REDIS_KEY_PREFIX_VERSION + key);
            logger.info("Deleted version: {}", key);
            return true;
        }
        return false;
    }

    @Override
    public String generateVersion(Map<String, Object> data) {
        String timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
        String checksum = calculateChecksum(data);
        return "v_" + timestamp.hashCode() + "_" + checksum.substring(0, 8);
    }

    @Override
    public String calculateChecksum(Object data) {
        try {
            String json = objectMapper.writeValueAsString(data);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(json.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return "sha256:" + hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            logger.error("SHA-256 algorithm not found", e);
            return "error:" + e.getMessage();
        } catch (Exception e) {
            logger.warn("Failed to calculate checksum", e);
            return "error:" + e.getMessage();
        }
    }

    @Override
    public boolean compareVersions(DataVersion source, DataVersion target) {
        if (source == null && target == null) {
            return true;
        }
        if (source == null || target == null) {
            return false;
        }
        if (source.hasSameChecksum(target)) {
            return true;
        }
        return Objects.equals(source.getVersion(), target.getVersion());
    }

    @Override
    public void updateVersion(String dataSource, String dataKey, String version, String checksum) {
        DataVersion dataVersion = new DataVersion();
        dataVersion.setDataSource(dataSource);
        dataVersion.setDataKey(dataKey);
        dataVersion.setVersion(version);
        dataVersion.setChecksum(checksum);
        dataVersion.setVersionId("ver_" + dataKey + "_" + version);
        saveVersion(dataVersion);
    }

    private void saveToRedis(String key, Object value) {
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(key, json);
        } catch (Exception e) {
            logger.debug("Failed to save version to Redis: {}", key, e);
        }
    }

    private void deleteFromRedis(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            logger.debug("Failed to delete version from Redis: {}", key, e);
        }
    }
}
