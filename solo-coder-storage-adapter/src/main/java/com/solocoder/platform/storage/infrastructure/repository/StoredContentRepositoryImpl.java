package com.solocoder.platform.storage.infrastructure.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.solocoder.platform.persistence.entity.StorageContentEntity;
import com.solocoder.platform.persistence.mapper.StorageContentMapper;
import com.solocoder.platform.storage.domain.model.StoredContent;
import com.solocoder.platform.storage.domain.repository.StoredContentRepository;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@RequiredArgsConstructor
public class StoredContentRepositoryImpl implements StoredContentRepository {

    private final StorageContentMapper storageContentMapper;

    @Override
    public StoredContent save(StoredContent content) {
        StorageContentEntity entity = toEntity(content);
        if (entity.getId() == null) {
            storageContentMapper.insert(entity);
        } else {
            storageContentMapper.updateById(entity);
        }
        return toDomain(entity);
    }

    @Override
    public Optional<StoredContent> findByContentId(String contentId) {
        LambdaQueryWrapper<StorageContentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StorageContentEntity::getContentId, contentId);
        StorageContentEntity entity = storageContentMapper.selectOne(wrapper);
        return Optional.ofNullable(entity).map(this::toDomain);
    }

    @Override
    public List<StoredContent> findByStorageType(StoredContent.StorageType storageType, int limit) {
        Page<StorageContentEntity> page = new Page<>(1, limit);
        LambdaQueryWrapper<StorageContentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StorageContentEntity::getStorageType, storageType.name())
                .orderByDesc(StorageContentEntity::getCreatedAt);
        return storageContentMapper.selectPage(page, wrapper).getRecords().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<StoredContent> findByPinStatus(StoredContent.PinStatus pinStatus, int limit) {
        Page<StorageContentEntity> page = new Page<>(1, limit);
        LambdaQueryWrapper<StorageContentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StorageContentEntity::getPinStatus, pinStatus.name())
                .orderByDesc(StorageContentEntity::getCreatedAt);
        return storageContentMapper.selectPage(page, wrapper).getRecords().stream()
                .map(this::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean deleteByContentId(String contentId) {
        LambdaQueryWrapper<StorageContentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StorageContentEntity::getContentId, contentId);
        return storageContentMapper.delete(wrapper) > 0;
    }

    @Override
    public boolean existsByContentId(String contentId) {
        LambdaQueryWrapper<StorageContentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StorageContentEntity::getContentId, contentId);
        return storageContentMapper.selectCount(wrapper) > 0;
    }

    private StoredContent toDomain(StorageContentEntity entity) {
        if (entity == null) {
            return null;
        }
        return StoredContent.builder()
                .id(entity.getId())
                .contentId(entity.getContentId())
                .contentHash(entity.getContentHash())
                .storageType(StoredContent.StorageType.valueOf(entity.getStorageType()))
                .network(entity.getNetwork())
                .size(entity.getSize())
                .mimeType(entity.getMimeType())
                .pinStatus(entity.getPinStatus() != null ? StoredContent.PinStatus.valueOf(entity.getPinStatus()) : null)
                .pinLocation(entity.getPinLocation())
                .replicationCount(entity.getReplicationCount())
                .expireTime(entity.getExpireTime())
                .metadata(entity.getMetadata() != null ? JSON.parseObject(entity.getMetadata(), new TypeReference<Map<String, Object>>() {}) : null)
                .createdBy(entity.getCreatedBy())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private StorageContentEntity toEntity(StoredContent domain) {
        if (domain == null) {
            return null;
        }
        StorageContentEntity entity = new StorageContentEntity();
        entity.setId(domain.getId());
        entity.setContentId(domain.getContentId());
        entity.setContentHash(domain.getContentHash());
        entity.setStorageType(domain.getStorageType() != null ? domain.getStorageType().name() : null);
        entity.setNetwork(domain.getNetwork());
        entity.setSize(domain.getSize());
        entity.setMimeType(domain.getMimeType());
        entity.setPinStatus(domain.getPinStatus() != null ? domain.getPinStatus().name() : null);
        entity.setPinLocation(domain.getPinLocation());
        entity.setReplicationCount(domain.getReplicationCount());
        entity.setExpireTime(domain.getExpireTime());
        entity.setMetadata(domain.getMetadata() != null ? JSON.toJSONString(domain.getMetadata()) : null);
        entity.setCreatedBy(domain.getCreatedBy());
        return entity;
    }
}
