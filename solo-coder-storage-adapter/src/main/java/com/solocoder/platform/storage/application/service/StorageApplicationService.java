package com.solocoder.platform.storage.application.service;

import com.solocoder.platform.storage.domain.model.StoredContent;
import com.solocoder.platform.storage.domain.repository.StoredContentRepository;
import com.solocoder.platform.storage.domain.service.ContentHashCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageApplicationService {

    private final ContentHashCalculator contentHashCalculator;
    private final StoredContentRepository storedContentRepository;

    @Transactional(rollbackFor = Exception.class)
    public StoredContent upload(String content, String contentType,
                                 String storageType, String network,
                                 boolean pin, String pinLocation,
                                 Map<String, Object> metadata, String createdBy) {
        byte[] contentBytes = content.getBytes();
        return uploadInternal(contentBytes, contentBytes.length, contentType,
                storageType, network, pin, pinLocation, metadata, createdBy);
    }

    @Transactional(rollbackFor = Exception.class)
    public StoredContent upload(byte[] content, String contentType,
                                 String storageType, String network,
                                 boolean pin, String pinLocation,
                                 Map<String, Object> metadata, String createdBy) {
        return uploadInternal(content, content.length, contentType,
                storageType, network, pin, pinLocation, metadata, createdBy);
    }

    @Transactional(rollbackFor = Exception.class)
    public StoredContent upload(InputStream content, long size, String contentType,
                                 String storageType, String network,
                                 boolean pin, String pinLocation,
                                 Map<String, Object> metadata, String createdBy) {
        String contentHash = contentHashCalculator.calculateContentHash(content, size);
        String contentId = contentHashCalculator.calculateContentId(null, storageType);

        StoredContent storedContent = StoredContent.builder()
                .contentId(contentId)
                .contentHash(contentHash)
                .storageType(StoredContent.StorageType.valueOf(storageType.toUpperCase()))
                .network(network != null ? network : "mainnet")
                .size(size)
                .mimeType(contentType)
                .pinStatus(pin ? StoredContent.PinStatus.PINNED : StoredContent.PinStatus.UNPINNED)
                .pinLocation(pinLocation)
                .replicationCount(1)
                .metadata(metadata)
                .createdBy(createdBy != null ? createdBy : "system")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        return storedContentRepository.save(storedContent);
    }

    private StoredContent uploadInternal(byte[] content, long size, String contentType,
                                          String storageType, String network,
                                          boolean pin, String pinLocation,
                                          Map<String, Object> metadata, String createdBy) {
        String contentHash = contentHashCalculator.calculateContentHash(content);
        String contentId = contentHashCalculator.calculateContentId(content, storageType);

        StoredContent storedContent = StoredContent.builder()
                .contentId(contentId)
                .contentHash(contentHash)
                .storageType(StoredContent.StorageType.valueOf(storageType.toUpperCase()))
                .network(network != null ? network : "mainnet")
                .size(size)
                .mimeType(contentType)
                .pinStatus(pin ? StoredContent.PinStatus.PINNED : StoredContent.PinStatus.UNPINNED)
                .pinLocation(pinLocation)
                .replicationCount(1)
                .metadata(metadata)
                .createdBy(createdBy != null ? createdBy : "system")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        return storedContentRepository.save(storedContent);
    }

    public StoredContent getContentInfo(String contentId) {
        return storedContentRepository.findByContentId(contentId)
                .orElseThrow(() -> new IllegalArgumentException("内容不存在: " + contentId));
    }

    public byte[] getContent(String contentId) {
        StoredContent content = getContentInfo(contentId);
        return new byte[0];
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean pinContent(String contentId, String location) {
        StoredContent content = getContentInfo(contentId);
        content.setPinStatus(StoredContent.PinStatus.PINNED);
        content.setPinLocation(location);
        content.setUpdatedAt(LocalDateTime.now());
        storedContentRepository.save(content);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean unpinContent(String contentId) {
        StoredContent content = getContentInfo(contentId);
        content.setPinStatus(StoredContent.PinStatus.UNPINNED);
        content.setUpdatedAt(LocalDateTime.now());
        storedContentRepository.save(content);
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteContent(String contentId) {
        return storedContentRepository.deleteByContentId(contentId);
    }

    public String getGatewayUrl(String contentId) {
        StoredContent content = getContentInfo(contentId);
        return contentHashCalculator.getGatewayUrl(contentId, content.getStorageType().name());
    }

    public List<StoredContent> findByStorageType(String storageType, int limit) {
        return storedContentRepository.findByStorageType(
                StoredContent.StorageType.valueOf(storageType.toUpperCase()), limit);
    }

    public List<StoredContent> findByPinStatus(String pinStatus, int limit) {
        return storedContentRepository.findByPinStatus(
                StoredContent.PinStatus.valueOf(pinStatus.toUpperCase()), limit);
    }
}
