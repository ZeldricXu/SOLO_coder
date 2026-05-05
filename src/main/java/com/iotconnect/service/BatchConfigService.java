package com.iotconnect.service;

import com.iotconnect.config.BatchConfigProperties;
import com.iotconnect.entity.DeviceTypeBatchConfig;
import com.iotconnect.repository.DeviceTypeBatchConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BatchConfigService {

    private static final Logger logger = LoggerFactory.getLogger(BatchConfigService.class);

    private final DeviceTypeBatchConfigRepository configRepository;
    private final BatchConfigProperties batchConfigProperties;

    @Value("${batch.processing.batch-size:100}")
    private int defaultBatchSize;

    @Value("${batch.processing.window-seconds:5}")
    private int defaultWindowSeconds;

    @Value("${batch.processing.max-buffer-size:10000}")
    private int defaultMaxBufferSize;

    private final ConcurrentHashMap<String, BatchConfig> configCache = new ConcurrentHashMap<>();
    private volatile BatchConfig defaultConfigCache;

    public BatchConfigService(DeviceTypeBatchConfigRepository configRepository,
                               BatchConfigProperties batchConfigProperties) {
        this.configRepository = configRepository;
        this.batchConfigProperties = batchConfigProperties;
    }

    @PostConstruct
    public void init() {
        BatchConfigProperties.DefaultConfig defaultProps = batchConfigProperties.getDefault();
        
        this.defaultBatchSize = defaultProps.getBatchSize();
        this.defaultWindowSeconds = defaultProps.getWindowSeconds();
        this.defaultMaxBufferSize = defaultProps.getMaxBufferSize();
        
        this.defaultConfigCache = new BatchConfig(defaultBatchSize, defaultWindowSeconds, defaultMaxBufferSize);
        
        logger.info("Initializing BatchConfigService with defaults: batchSize={}, windowSeconds={}, maxBufferSize={}",
                defaultBatchSize, defaultWindowSeconds, defaultMaxBufferSize);
        
        loadAllConfigs();
        loadConfigProperties();
    }

    private void loadConfigProperties() {
        Map<String, BatchConfigProperties.TypeConfig> typeConfigs = batchConfigProperties.getTypes();
        
        if (typeConfigs == null || typeConfigs.isEmpty()) {
            logger.debug("No type configurations found in application.yml");
            return;
        }

        for (Map.Entry<String, BatchConfigProperties.TypeConfig> entry : typeConfigs.entrySet()) {
            String deviceType = entry.getKey();
            BatchConfigProperties.TypeConfig typeConfig = entry.getValue();
            
            if (typeConfig.getEnabled() != null && !typeConfig.getEnabled()) {
                continue;
            }

            int batchSize = typeConfig.getBatchSize() != null ? typeConfig.getBatchSize() : defaultBatchSize;
            int windowSeconds = typeConfig.getWindowSeconds() != null ? typeConfig.getWindowSeconds() : defaultWindowSeconds;
            int maxBufferSize = typeConfig.getMaxBufferSize() != null ? typeConfig.getMaxBufferSize() : defaultMaxBufferSize;

            BatchConfig config = new BatchConfig(batchSize, windowSeconds, maxBufferSize);
            configCache.put(deviceType, config);
            
            logger.info("Loaded batch config from properties: deviceType={}, batchSize={}, windowSeconds={}, maxBufferSize={}",
                    deviceType, batchSize, windowSeconds, maxBufferSize);
        }
    }

    @Transactional
    public void loadAllConfigs() {
        List<DeviceTypeBatchConfig> configs = configRepository.findByEnabledTrue();
        
        for (DeviceTypeBatchConfig config : configs) {
            configCache.put(config.getDeviceType(), 
                    new BatchConfig(config.getBatchSize(), config.getWindowSeconds(), config.getMaxBufferSize()));
        }
        
        logger.info("Loaded {} device type batch configurations from database", configs.size());
    }

    public BatchConfig getConfig(String deviceType) {
        BatchConfig config = configCache.get(deviceType);
        
        if (config == null) {
            Optional<DeviceTypeBatchConfig> configOpt = configRepository.findByDeviceTypeAndEnabledTrue(deviceType);
            
            if (configOpt.isPresent()) {
                DeviceTypeBatchConfig dbConfig = configOpt.get();
                config = new BatchConfig(dbConfig.getBatchSize(), dbConfig.getWindowSeconds(), dbConfig.getMaxBufferSize());
                configCache.put(deviceType, config);
                logger.debug("Loaded batch config from database: deviceType={}, {}", deviceType, config);
            } else {
                BatchConfigProperties.TypeConfig typeConfig = batchConfigProperties.getTypes().get(deviceType);
                if (typeConfig != null && (typeConfig.getEnabled() == null || typeConfig.getEnabled())) {
                    int batchSize = typeConfig.getBatchSize() != null ? typeConfig.getBatchSize() : defaultBatchSize;
                    int windowSeconds = typeConfig.getWindowSeconds() != null ? typeConfig.getWindowSeconds() : defaultWindowSeconds;
                    int maxBufferSize = typeConfig.getMaxBufferSize() != null ? typeConfig.getMaxBufferSize() : defaultMaxBufferSize;
                    
                    config = new BatchConfig(batchSize, windowSeconds, maxBufferSize);
                    configCache.put(deviceType, config);
                    logger.debug("Loaded batch config from properties: deviceType={}, {}", deviceType, config);
                } else {
                    config = getDefaultConfig();
                    logger.debug("Using default batch config for deviceType={}", deviceType);
                }
            }
        }
        
        return config;
    }

    public BatchConfig getDefaultConfig() {
        return defaultConfigCache != null ? defaultConfigCache : 
                new BatchConfig(defaultBatchSize, defaultWindowSeconds, defaultMaxBufferSize);
    }

    @Transactional
    public DeviceTypeBatchConfig createConfig(DeviceTypeBatchConfig config) {
        if (configRepository.existsByDeviceType(config.getDeviceType())) {
            throw new RuntimeException("Config already exists for device type: " + config.getDeviceType());
        }
        
        config.setCreatedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        
        if (config.getEnabled() == null) {
            config.setEnabled(true);
        }
        
        if (config.getMaxBufferSize() == null) {
            config.setMaxBufferSize(defaultMaxBufferSize);
        }

        DeviceTypeBatchConfig saved = configRepository.save(config);
        
        if (Boolean.TRUE.equals(config.getEnabled())) {
            configCache.put(config.getDeviceType(),
                    new BatchConfig(config.getBatchSize(), config.getWindowSeconds(), config.getMaxBufferSize()));
        }

        logger.info("Created batch config for deviceType={}", config.getDeviceType());
        return saved;
    }

    @Transactional
    public DeviceTypeBatchConfig updateConfig(String deviceType, DeviceTypeBatchConfig updatedConfig) {
        Optional<DeviceTypeBatchConfig> existingOpt = configRepository.findByDeviceType(deviceType);
        
        if (existingOpt.isEmpty()) {
            throw new RuntimeException("Config not found for device type: " + deviceType);
        }

        DeviceTypeBatchConfig existing = existingOpt.get();
        
        if (updatedConfig.getBatchSize() != null) {
            existing.setBatchSize(updatedConfig.getBatchSize());
        }
        if (updatedConfig.getWindowSeconds() != null) {
            existing.setWindowSeconds(updatedConfig.getWindowSeconds());
        }
        if (updatedConfig.getMaxBufferSize() != null) {
            existing.setMaxBufferSize(updatedConfig.getMaxBufferSize());
        }
        if (updatedConfig.getDescription() != null) {
            existing.setDescription(updatedConfig.getDescription());
        }
        if (updatedConfig.getEnabled() != null) {
            existing.setEnabled(updatedConfig.getEnabled());
        }
        
        existing.setUpdatedAt(LocalDateTime.now());

        DeviceTypeBatchConfig saved = configRepository.save(existing);
        
        if (Boolean.TRUE.equals(saved.getEnabled())) {
            configCache.put(deviceType,
                    new BatchConfig(saved.getBatchSize(), saved.getWindowSeconds(), saved.getMaxBufferSize()));
        } else {
            configCache.remove(deviceType);
        }

        logger.info("Updated batch config for deviceType={}", deviceType);
        return saved;
    }

    @Transactional
    public void deleteConfig(String deviceType) {
        configRepository.deleteByDeviceType(deviceType);
        configCache.remove(deviceType);
        logger.info("Deleted batch config for deviceType={}", deviceType);
    }

    public List<DeviceTypeBatchConfig> getAllConfigs() {
        return configRepository.findAll();
    }

    public Optional<DeviceTypeBatchConfig> getConfigByDeviceType(String deviceType) {
        return configRepository.findByDeviceType(deviceType);
    }

    public void refreshCache() {
        configCache.clear();
        loadAllConfigs();
        loadConfigProperties();
        logger.info("Batch config cache refreshed");
    }

    public Map<String, BatchConfig> getAllCachedConfigs() {
        return new ConcurrentHashMap<>(configCache);
    }

    public static class BatchConfig {
        private final int batchSize;
        private final int windowSeconds;
        private final int maxBufferSize;

        public BatchConfig(int batchSize, int windowSeconds, int maxBufferSize) {
            this.batchSize = batchSize;
            this.windowSeconds = windowSeconds;
            this.maxBufferSize = maxBufferSize;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public int getMaxBufferSize() {
            return maxBufferSize;
        }

        @Override
        public String toString() {
            return "BatchConfig{" +
                    "batchSize=" + batchSize +
                    ", windowSeconds=" + windowSeconds +
                    ", maxBufferSize=" + maxBufferSize +
                    '}';
        }
    }
}
