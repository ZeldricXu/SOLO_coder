package com.parking.platform.storage.service;

import com.parking.platform.storage.entity.StoredObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final Map<String, StoredObject> objectStore = new ConcurrentHashMap<>();
    private final Map<String, byte[]> contentStore = new ConcurrentHashMap<>();

    public StoredObject upload(String bucket, String key, byte[] content, String contentType, Map<String, String> metadata) {
        StoredObject obj = new StoredObject();
        obj.setBucket(bucket);
        obj.setKey(key);
        obj.setContentType(contentType);
        obj.setSize((long) content.length);
        obj.setProvider("local");
        obj.setEtag(Integer.toHexString(Arrays.hashCode(content)));
        obj.setMetadata(metadata != null ? metadata : new HashMap<>());

        String storeKey = bucket + "/" + key;
        objectStore.put(storeKey, obj);
        contentStore.put(storeKey, content);

        log.info("Object uploaded: {}/{}", bucket, key);
        return obj;
    }

    public byte[] download(String bucket, String key) {
        String storeKey = bucket + "/" + key;
        byte[] content = contentStore.get(storeKey);
        if (content == null) {
            throw new RuntimeException("Object not found: " + storeKey);
        }
        log.debug("Object downloaded: {}/{}", bucket, key);
        return content;
    }

    public StoredObject getObjectMetadata(String bucket, String key) {
        String storeKey = bucket + "/" + key;
        return objectStore.get(storeKey);
    }

    public boolean delete(String bucket, String key) {
        String storeKey = bucket + "/" + key;
        objectStore.remove(storeKey);
        byte[] removed = contentStore.remove(storeKey);
        if (removed != null) {
            log.info("Object deleted: {}/{}", bucket, key);
            return true;
        }
        return false;
    }

    public List<StoredObject> listObjects(String bucket, String prefix, Integer page, Integer size) {
        List<StoredObject> objects = new ArrayList<>();
        for (Map.Entry<String, StoredObject> entry : objectStore.entrySet()) {
            StoredObject obj = entry.getValue();
            if (bucket.equals(obj.getBucket())) {
                if (prefix == null || obj.getKey().startsWith(prefix)) {
                    objects.add(obj);
                }
            }
        }
        objects.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        int pageNum = page != null ? page : 1;
        int sizeNum = size != null ? size : 50;
        int start = (pageNum - 1) * sizeNum;
        int end = Math.min(start + sizeNum, objects.size());

        return start >= objects.size() ? new ArrayList<>() : objects.subList(start, end);
    }

    public String generatePresignedUrl(String bucket, String key, long expirationMinutes) {
        StoredObject obj = getObjectMetadata(bucket, key);
        if (obj == null) {
            return null;
        }
        String token = UUID.randomUUID().toString();
        return "/api/v1/storage/" + bucket + "/" + key + "?token=" + token + "&expires=" + (Instant.now().getEpochSecond() + expirationMinutes * 60);
    }

    public Map<String, Long> getStatistics() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("totalObjects", (long) objectStore.size());
        stats.put("totalSize", contentStore.values().stream().mapToLong(c -> c.length).sum());
        return stats;
    }
}
