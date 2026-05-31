package com.solocoder.platform.storage.adapter.api;

import com.solocoder.platform.storage.api.StorageAdapterApi;
import com.solocoder.platform.storage.application.service.StorageApplicationService;
import com.solocoder.platform.storage.domain.model.StoredContent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class StorageAdapterApiImpl implements StorageAdapterApi {

    private final StorageApplicationService storageApplicationService;

    @Override
    public StoredContent upload(String content, String contentType, Map<String, Object> metadata) {
        StoredContent stored = storageApplicationService.upload(
                content, contentType, StorageType.IPFS.name(), null, true, null, metadata, "internal");
        return toApiStoredContent(stored);
    }

    @Override
    public StoredContent upload(byte[] content, String contentType, Map<String, Object> metadata) {
        StoredContent stored = storageApplicationService.upload(
                content, contentType, StorageType.IPFS.name(), null, true, null, metadata, "internal");
        return toApiStoredContent(stored);
    }

    @Override
    public StoredContent upload(InputStream content, long size, String contentType, Map<String, Object> metadata) {
        StoredContent stored = storageApplicationService.upload(
                content, size, contentType, StorageType.IPFS.name(), null, true, null, metadata, "internal");
        return toApiStoredContent(stored);
    }

    @Override
    public byte[] getContent(String contentId) {
        return storageApplicationService.getContent(contentId);
    }

    @Override
    public StoredContent getContentInfo(String contentId) {
        StoredContent stored = storageApplicationService.getContentInfo(contentId);
        return toApiStoredContent(stored);
    }

    @Override
    public boolean pinContent(String contentId, String location) {
        return storageApplicationService.pinContent(contentId, location);
    }

    @Override
    public boolean unpinContent(String contentId) {
        return storageApplicationService.unpinContent(contentId);
    }

    @Override
    public boolean deleteContent(String contentId) {
        return storageApplicationService.deleteContent(contentId);
    }

    @Override
    public StorageType getStorageType() {
        return StorageType.IPFS;
    }

    private StoredContent toApiStoredContent(com.solocoder.platform.storage.domain.model.StoredContent domain) {
        return new StoredContent() {
            @Override
            public String getContentId() {
                return domain.getContentId();
            }

            @Override
            public String getContentHash() {
                return domain.getContentHash();
            }

            @Override
            public String getStorageType() {
                return domain.getStorageType().name();
            }

            @Override
            public String getMimeType() {
                return domain.getMimeType();
            }

            @Override
            public Long getSize() {
                return domain.getSize();
            }

            @Override
            public String getPinStatus() {
                return domain.getPinStatus() != null ? domain.getPinStatus().name() : null;
            }

            @Override
            public String getGatewayUrl() {
                return storageApplicationService.getGatewayUrl(domain.getContentId());
            }

            @Override
            public Map<String, Object> getMetadata() {
                return domain.getMetadata();
            }
        };
    }
}
