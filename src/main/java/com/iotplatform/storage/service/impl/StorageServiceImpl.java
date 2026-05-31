package com.iotplatform.storage.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.iotplatform.common.exception.BusinessException;
import com.iotplatform.storage.dto.ObjectDownloadDTO;
import com.iotplatform.storage.dto.ObjectUploadDTO;
import com.iotplatform.storage.entity.StorageObject;
import com.iotplatform.storage.mapper.StorageObjectMapper;
import com.iotplatform.storage.service.StorageService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class StorageServiceImpl implements StorageService {

    private final StorageObjectMapper objectMapper;
    private final ReactiveStringRedisTemplate redisTemplate;
    private final MeterRegistry meterRegistry;

    @Value("${iot.storage.providers.minio.bucket:iot-data}")
    private String defaultBucket;

    @Value("${iot.storage.providers.minio.endpoint:http://localhost:9000}")
    private String storageEndpoint;

    private final Cache<String, StorageObject> objectCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();

    private static final String STORAGE_BASE_PATH = "./data/storage";
    private static final String OBJECT_CACHE_PREFIX = "storage:object:";

    @Override
    @Transactional
    public Mono<StorageObject> uploadObject(ObjectUploadDTO dto) {
        Timer.Sample sample = Timer.start(meterRegistry);
        return Mono.fromCallable(() -> {
            try {
                String provider = dto.getProvider() != null ? dto.getProvider() : StorageObject.Provider.LOCAL;
                byte[] content = dto.getContent() != null ? dto.getContent() : new byte[0];
                byte[] compressedContent = compress(content);

                String etag = DigestUtil.md5Hex(content);
                String objectId = "obj_" + IdUtil.getSnowflakeNextIdStr();

                StorageObject object = new StorageObject();
                object.setObjectId(objectId);
                object.setBucketName(dto.getBucketName() != null ? dto.getBucketName() : defaultBucket);
                object.setObjectKey(dto.getObjectKey());
                object.setObjectName(dto.getObjectName() != null ? dto.getObjectName() : dto.getObjectKey());
                object.setContentType(dto.getContentType() != null ? dto.getContentType() : "application/octet-stream");
                object.setContentLength((long) content.length);
                object.setEtag(etag);
                object.setProvider(provider);
                object.setMetadata(dto.getMetadata() != null ? JSONUtil.toJsonStr(dto.getMetadata()) : null);
                object.setTags(dto.getTags() != null ? JSONUtil.toJsonStr(dto.getTags()) : null);
                object.setCreatedBy(dto.getCreatedBy());

                saveToLocalStorage(object, compressedContent);
                objectMapper.insert(object);

                String cacheKey = OBJECT_CACHE_PREFIX + objectId;
                objectCache.put(cacheKey, object);
                redisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(object), Duration.ofMinutes(10)).subscribe();

                log.info("Object uploaded: {} - {} ({} bytes)", objectId, dto.getObjectKey(), content.length);
                meterRegistry.counter("storage.object.uploaded", "provider", provider).increment();
                meterRegistry.summary("storage.object.size", "provider", provider).record(content.length);

                return object;
            } catch (Exception e) {
                log.error("Failed to upload object: {}", e.getMessage(), e);
                meterRegistry.counter("storage.object.upload.failed").increment();
                throw new BusinessException("上传对象失败: " + e.getMessage());
            } finally {
                sample.stop(meterRegistry.timer("storage.upload.latency"));
            }
        });
    }

    @Override
    public Mono<ObjectDownloadDTO> downloadObject(String objectId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        return Mono.fromCallable(() -> {
            try {
                StorageObject object = getObjectFromCacheOrDb(objectId);
                byte[] content = readFromLocalStorage(object);
                byte[] decompressedContent = decompress(content);

                ObjectDownloadDTO dto = new ObjectDownloadDTO();
                dto.setObjectId(object.getObjectId());
                dto.setObjectName(object.getObjectName());
                dto.setContentType(object.getContentType());
                dto.setContentLength(object.getContentLength());
                dto.setContent(decompressedContent);
                dto.setEtag(object.getEtag());

                log.debug("Object downloaded: {} - {}", objectId, object.getObjectKey());
                meterRegistry.counter("storage.object.downloaded").increment();
                return dto;
            } catch (Exception e) {
                log.error("Failed to download object: {}", e.getMessage(), e);
                meterRegistry.counter("storage.object.download.failed").increment();
                throw new BusinessException("下载对象失败: " + e.getMessage());
            } finally {
                sample.stop(meterRegistry.timer("storage.download.latency"));
            }
        });
    }

    @Override
    public Mono<StorageObject> getObjectMetadata(String objectId) {
        return Mono.fromCallable(() -> getObjectFromCacheOrDb(objectId));
    }

    @Override
    @Transactional
    public Mono<Void> deleteObject(String objectId) {
        return Mono.fromCallable(() -> {
            StorageObject object = getObjectFromCacheOrDb(objectId);
            objectMapper.deleteById(object.getId());

            deleteFromLocalStorage(object);
            String cacheKey = OBJECT_CACHE_PREFIX + objectId;
            objectCache.invalidate(cacheKey);
            redisTemplate.delete(cacheKey).subscribe();

            log.info("Object deleted: {} - {}", objectId, object.getObjectKey());
            meterRegistry.counter("storage.object.deleted").increment();
            return null;
        });
    }

    @Override
    public Mono<IPage<StorageObject>> listObjects(String bucketName, String prefix, String provider,
                                                  Integer pageNum, Integer pageSize) {
        return Mono.fromCallable(() -> {
            Page<StorageObject> page = new Page<>(pageNum, pageSize);
            return objectMapper.selectObjectPage(page, bucketName, prefix, provider);
        });
    }

    @Override
    public Mono<Map<String, Object>> getBucketStats(String bucketName) {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();
            stats.put("bucketName", bucketName);
            stats.put("objectCount", objectMapper.countByBucket(bucketName));
            Long totalSize = objectMapper.sumContentLengthByBucket(bucketName);
            stats.put("totalSizeBytes", totalSize != null ? totalSize : 0);
            stats.put("totalSizeMB", totalSize != null ? totalSize / (1024.0 * 1024.0) : 0);
            return stats;
        });
    }

    @Override
    public Mono<String> generatePresignedUrl(String objectId, long expirySeconds) {
        return Mono.fromCallable(() -> {
            StorageObject object = getObjectFromCacheOrDb(objectId);
            String token = IdUtil.fastSimpleUUID();
            String urlKey = "storage:presigned:" + token;
            redisTemplate.opsForValue().set(urlKey, objectId, Duration.ofSeconds(expirySeconds)).subscribe();
            return storageEndpoint + "/api/v1/storage/presigned/" + token;
        });
    }

    @Override
    @Transactional
    public Mono<List<StorageObject>> batchDelete(List<String> objectIds) {
        return Mono.fromCallable(() -> {
            List<StorageObject> objects = objectIds.stream()
                    .map(this::getObjectFromCacheOrDb)
                    .toList();
            for (StorageObject object : objects) {
                objectMapper.deleteById(object.getId());
                deleteFromLocalStorage(object);
                objectCache.invalidate(OBJECT_CACHE_PREFIX + object.getObjectId());
            }
            log.info("Batch deleted {} objects", objectIds.size());
            return objects;
        });
    }

    @Override
    public Mono<Boolean> objectExists(String bucketName, String objectKey) {
        return Mono.fromCallable(() -> objectMapper.findByBucketAndKey(bucketName, objectKey).isPresent());
    }

    @Override
    @Transactional
    public Mono<Void> copyObject(String sourceObjectId, String destBucket, String destKey) {
        return Mono.fromCallable(() -> {
            StorageObject source = getObjectFromCacheOrDb(sourceObjectId);
            byte[] content = readFromLocalStorage(source);

            StorageObject copy = new StorageObject();
            copy.setObjectId("obj_" + IdUtil.getSnowflakeNextIdStr());
            copy.setBucketName(destBucket);
            copy.setObjectKey(destKey);
            copy.setObjectName(source.getObjectName());
            copy.setContentType(source.getContentType());
            copy.setContentLength(source.getContentLength());
            copy.setEtag(source.getEtag());
            copy.setProvider(source.getProvider());
            copy.setMetadata(source.getMetadata());

            saveToLocalStorage(copy, content);
            objectMapper.insert(copy);

            log.info("Object copied: {} -> {}:{}", sourceObjectId, destBucket, destKey);
            return null;
        });
    }

    private StorageObject getObjectFromCacheOrDb(String objectId) {
        String cacheKey = OBJECT_CACHE_PREFIX + objectId;
        StorageObject cached = objectCache.getIfPresent(cacheKey);
        if (cached != null) {
            meterRegistry.counter("storage.cache.hit").increment();
            return cached;
        }

        meterRegistry.counter("storage.cache.miss").increment();
        StorageObject object = objectMapper.findByObjectId(objectId)
                .orElseThrow(() -> new BusinessException(404, "对象不存在: " + objectId));
        objectCache.put(cacheKey, object);
        return object;
    }

    private void saveToLocalStorage(StorageObject object, byte[] content) throws IOException {
        Path dirPath = Paths.get(STORAGE_BASE_PATH, object.getBucketName());
        Files.createDirectories(dirPath);
        Path filePath = dirPath.resolve(object.getObjectId());
        Files.write(filePath, content);
    }

    private byte[] readFromLocalStorage(StorageObject object) throws IOException {
        Path filePath = Paths.get(STORAGE_BASE_PATH, object.getBucketName(), object.getObjectId());
        return Files.readAllBytes(filePath);
    }

    private void deleteFromLocalStorage(StorageObject object) {
        try {
            Path filePath = Paths.get(STORAGE_BASE_PATH, object.getBucketName(), object.getObjectId());
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            log.warn("Failed to delete local file: {}", e.getMessage());
        }
    }

    private byte[] compress(byte[] content) throws IOException {
        if (content == null || content.length == 0) {
            return content;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content);
        }
        return out.toByteArray();
    }

    private byte[] decompress(byte[] content) throws IOException {
        if (content == null || content.length == 0) {
            return content;
        }
        try (java.util.zip.GZIPInputStream gzip = new java.util.zip.GZIPInputStream(new ByteArrayInputStream(content))) {
            return gzip.readAllBytes();
        }
    }
}
