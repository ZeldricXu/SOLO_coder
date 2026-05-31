package com.solocoder.platform.storage.domain.repository;

import com.solocoder.platform.storage.domain.model.StoredContent;

import java.util.List;
import java.util.Optional;

public interface StoredContentRepository {

    StoredContent save(StoredContent content);

    Optional<StoredContent> findByContentId(String contentId);

    List<StoredContent> findByStorageType(StoredContent.StorageType storageType, int limit);

    List<StoredContent> findByPinStatus(StoredContent.PinStatus pinStatus, int limit);

    boolean deleteByContentId(String contentId);

    boolean existsByContentId(String contentId);
}
