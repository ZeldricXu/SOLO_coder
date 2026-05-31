package com.solocoder.platform.storage.api;

import java.io.InputStream;
import java.util.Map;

public interface StorageAdapterApi {

    StoredContent upload(String content, String contentType, Map<String, Object> metadata);

    StoredContent upload(byte[] content, String contentType, Map<String, Object> metadata);

    StoredContent upload(InputStream content, long size, String contentType, Map<String, Object> metadata);

    byte[] getContent(String contentId);

    StoredContent getContentInfo(String contentId);

    boolean pinContent(String contentId, String location);

    boolean unpinContent(String contentId);

    boolean deleteContent(String contentId);

    StorageType getStorageType();

    enum StorageType {
        IPFS,
        ARWEAVE,
        FILECOIN
    }

    interface StoredContent {
        String getContentId();
        String getContentHash();
        String getStorageType();
        String getMimeType();
        Long getSize();
        String getPinStatus();
        String getGatewayUrl();
        Map<String, Object> getMetadata();
    }
}
