package com.cdcsync.lifecycle.core;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class ArchiveManager {

    private final Map<String, byte[]> archiveStorage = new HashMap<>();

    public void archive(String resourceId, Object data) {
        log.info("Archiving data: resourceId={}", resourceId);

        ArchiveEntry entry = new ArchiveEntry();
        entry.setResourceId(resourceId);
        entry.setData(JSON.toJSONString(data));
        entry.setArchivedAt(LocalDateTime.now());

        byte[] archiveBytes = JSON.toJSONBytes(entry);
        archiveStorage.put(resourceId, archiveBytes);

        log.info("Data archived successfully: resourceId={}, size={} bytes", resourceId, archiveBytes.length);
    }

    public Object restore(String resourceId) {
        log.info("Restoring archived data: resourceId={}", resourceId);

        byte[] archiveBytes = archiveStorage.get(resourceId);
        if (archiveBytes == null) {
            throw new RuntimeException("Archive not found: " + resourceId);
        }

        ArchiveEntry entry = JSON.parseObject(archiveBytes, ArchiveEntry.class);
        log.info("Data restored successfully: resourceId={}", resourceId);

        return entry.getData();
    }

    public boolean exists(String resourceId) {
        return archiveStorage.containsKey(resourceId);
    }

    public void deleteArchive(String resourceId) {
        log.info("Deleting archive: resourceId={}", resourceId);
        archiveStorage.remove(resourceId);
    }

    @lombok.Data
    public static class ArchiveEntry {
        private String resourceId;
        private String data;
        private LocalDateTime archivedAt;
    }
}
