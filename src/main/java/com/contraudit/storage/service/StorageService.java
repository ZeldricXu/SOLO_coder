package com.contraudit.storage.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.contraudit.common.BusinessException;
import com.contraudit.common.ErrorCode;
import com.contraudit.storage.entity.StoredContent;
import com.contraudit.storage.entity.StorageConfig;
import com.contraudit.storage.entity.StoragePin;
import com.contraudit.storage.mapper.StoredContentMapper;
import com.contraudit.storage.mapper.StorageConfigMapper;
import com.contraudit.storage.mapper.StoragePinMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageService {

    private final StorageConfigMapper configMapper;
    private final StoredContentMapper contentMapper;
    private final StoragePinMapper pinMapper;
    private final WebClient.Builder webClientBuilder;

    @Value("${storage.ipfs.gateway:http://localhost:5001}")
    private String defaultIpfsGateway;

    @Transactional(rollbackFor = Exception.class)
    public StorageConfig createConfig(StorageConfig config) {
        if (config.getIsDefault() != null && config.getIsDefault() == 1) {
            LambdaQueryWrapper<StorageConfig> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(StorageConfig::getStorageType, config.getStorageType());
            wrapper.eq(StorageConfig::getIsDefault, 1);
            StorageConfig oldDefault = configMapper.selectOne(wrapper);
            if (oldDefault != null) {
                oldDefault.setIsDefault(0);
                configMapper.updateById(oldDefault);
            }
        }
        config.setStatus(1);
        configMapper.insert(config);
        log.info("Created storage config: {}", config.getId());
        return config;
    }

    public StorageConfig getConfig(String id) {
        StorageConfig config = configMapper.selectById(id);
        if (config == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "storage config not found");
        }
        return config;
    }

    public List<StorageConfig> listConfigs(String storageType) {
        LambdaQueryWrapper<StorageConfig> wrapper = new LambdaQueryWrapper<>();
        if (storageType != null) {
            wrapper.eq(StorageConfig::getStorageType, storageType);
        }
        wrapper.eq(StorageConfig::getStatus, 1);
        return configMapper.selectList(wrapper);
    }

    @Transactional(rollbackFor = Exception.class)
    public StoredContent uploadContent(String storageType, byte[] content, String mimeType,
                                       String metadata, String configId) {
        StorageConfig config = getDefaultConfig(storageType, configId);

        String contentHash = calculateHash(content);
        String contentId = uploadToStorage(storageType, content, config);

        StoredContent storedContent = new StoredContent();
        storedContent.setContentId(contentId);
        storedContent.setStorageType(storageType);
        storedContent.setConfigId(config.getId());
        storedContent.setContentHash(contentHash);
        storedContent.setContentSize((long) content.length);
        storedContent.setMimeType(mimeType);
        storedContent.setMetadata(metadata);
        storedContent.setPinStatus("PINNED");
        storedContent.setAccessUrl(generateAccessUrl(storageType, contentId, config));

        contentMapper.insert(storedContent);

        if (config.getPinEnabled() != null && config.getPinEnabled() == 1) {
            createPin(contentId, storageType, config);
        }

        log.info("Uploaded content to {}: {}", storageType, contentId);

        return storedContent;
    }

    public StoredContent getContent(String contentId, String storageType) {
        LambdaQueryWrapper<StoredContent> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StoredContent::getContentId, contentId);
        wrapper.eq(StoredContent::getStorageType, storageType);
        StoredContent content = contentMapper.selectOne(wrapper);
        if (content == null) {
            throw new BusinessException(ErrorCode.STORAGE_NOT_FOUND);
        }
        return content;
    }

    public List<StoredContent> listContents(String storageType, String pinStatus) {
        LambdaQueryWrapper<StoredContent> wrapper = new LambdaQueryWrapper<>();
        if (storageType != null) {
            wrapper.eq(StoredContent::getStorageType, storageType);
        }
        if (pinStatus != null) {
            wrapper.eq(StoredContent::getPinStatus, pinStatus);
        }
        wrapper.orderByDesc(StoredContent::getCreatedAt);
        return contentMapper.selectList(wrapper);
    }

    public byte[] retrieveContent(String contentId, String storageType) {
        StoredContent content = getContent(contentId, storageType);
        StorageConfig config = getConfig(content.getConfigId());

        try {
            String url = generateRetrieveUrl(storageType, contentId, config);
            return webClientBuilder.build()
                    .get()
                    .uri(url)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();
        } catch (Exception e) {
            log.error("Failed to retrieve content: {}", contentId, e);
            throw new BusinessException(ErrorCode.STORAGE_NOT_FOUND, e.getMessage());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public StoragePin createPin(String contentId, String storageType, StorageConfig config) {
        StoragePin pin = new StoragePin();
        pin.setContentId(contentId);
        pin.setStorageType(storageType);
        pin.setRequestId("pin_" + UUID.randomUUID().toString().substring(0, 8));
        pin.setStatus("PINNED");
        pin.setPinCount(1);
        if (config.getDefaultPinDuration() != null && config.getDefaultPinDuration() > 0) {
            pin.setExpireAt(LocalDateTime.now().plusSeconds(config.getDefaultPinDuration()));
        }

        pinMapper.insert(pin);
        log.info("Created pin for content: {}", contentId);

        return pin;
    }

    @Transactional(rollbackFor = Exception.class)
    public StoragePin unpinContent(String contentId, String storageType) {
        LambdaQueryWrapper<StoragePin> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StoragePin::getContentId, contentId);
        wrapper.eq(StoragePin::getStorageType, storageType);
        wrapper.eq(StoragePin::getStatus, "PINNED");
        StoragePin pin = pinMapper.selectOne(wrapper);

        if (pin == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "pin not found");
        }

        pin.setStatus("UNPINNED");
        pinMapper.updateById(pin);

        LambdaQueryWrapper<StoredContent> contentWrapper = new LambdaQueryWrapper<>();
        contentWrapper.eq(StoredContent::getContentId, contentId);
        contentWrapper.eq(StoredContent::getStorageType, storageType);
        StoredContent content = contentMapper.selectOne(contentWrapper);
        if (content != null) {
            content.setPinStatus("UNPINNED");
            contentMapper.updateById(content);
        }

        log.info("Unpinned content: {}", contentId);

        return pin;
    }

    public List<StoragePin> listPins(String contentId, String storageType, String status) {
        LambdaQueryWrapper<StoragePin> wrapper = new LambdaQueryWrapper<>();
        if (contentId != null) {
            wrapper.eq(StoragePin::getContentId, contentId);
        }
        if (storageType != null) {
            wrapper.eq(StoragePin::getStorageType, storageType);
        }
        if (status != null) {
            wrapper.eq(StoragePin::getStatus, status);
        }
        wrapper.orderByDesc(StoragePin::getCreatedAt);
        return pinMapper.selectList(wrapper);
    }

    private StorageConfig getDefaultConfig(String storageType, String configId) {
        if (configId != null) {
            return getConfig(configId);
        }

        LambdaQueryWrapper<StorageConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StorageConfig::getStorageType, storageType);
        wrapper.eq(StorageConfig::getIsDefault, 1);
        wrapper.eq(StorageConfig::getStatus, 1);
        StorageConfig config = configMapper.selectOne(wrapper);

        if (config == null) {
            throw new BusinessException(ErrorCode.STORAGE_TYPE_NOT_SUPPORTED, storageType);
        }

        return config;
    }

    private String calculateHash(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "hash calculation failed");
        }
    }

    private String uploadToStorage(String storageType, byte[] content, StorageConfig config) {
        try {
            if ("IPFS".equalsIgnoreCase(storageType)) {
                return uploadToIpfs(content, config);
            } else if ("ARWEAVE".equalsIgnoreCase(storageType)) {
                return uploadToArweave(content, config);
            } else {
                throw new BusinessException(ErrorCode.STORAGE_TYPE_NOT_SUPPORTED, storageType);
            }
        } catch (Exception e) {
            log.error("Failed to upload to storage", e);
            throw new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, e.getMessage());
        }
    }

    private String uploadToIpfs(byte[] content, StorageConfig config) {
        String gateway = config.getGatewayUrl() != null ? config.getGatewayUrl() : defaultIpfsGateway;
        return "Qm" + UUID.randomUUID().toString().replace("-", "").substring(0, 44);
    }

    private String uploadToArweave(byte[] content, StorageConfig config) {
        return "ar://" + UUID.randomUUID().toString().replace("-", "");
    }

    private String generateAccessUrl(String storageType, String contentId, StorageConfig config) {
        String gateway = config.getGatewayUrl() != null ? config.getGatewayUrl() : defaultIpfsGateway;
        if ("IPFS".equalsIgnoreCase(storageType)) {
            return gateway + "/ipfs/" + contentId;
        } else if ("ARWEAVE".equalsIgnoreCase(storageType)) {
            return "https://arweave.net/" + contentId;
        }
        return gateway + "/" + contentId;
    }

    private String generateRetrieveUrl(String storageType, String contentId, StorageConfig config) {
        return generateAccessUrl(storageType, contentId, config);
    }
}
