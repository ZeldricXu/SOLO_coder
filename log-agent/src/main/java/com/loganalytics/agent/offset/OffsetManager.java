package com.loganalytics.agent.offset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.loganalytics.common.util.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class OffsetManager {
    private static final Logger log = LoggerFactory.getLogger(OffsetManager.class);

    private final String storePath;
    private final Map<String, FileOffset> offsets;
    private final ScheduledExecutorService scheduler;
    private volatile boolean dirty;

    public static class FileOffset {
        private String filePath;
        private long inode;
        private long offset;
        private long lastModified;
        private long lastReadTime;

        public FileOffset() {}

        public FileOffset(String filePath, long inode, long offset, long lastModified) {
            this.filePath = filePath;
            this.inode = inode;
            this.offset = offset;
            this.lastModified = lastModified;
            this.lastReadTime = System.currentTimeMillis();
        }

        public String getFilePath() { return filePath; }
        public void setFilePath(String filePath) { this.filePath = filePath; }

        public long getInode() { return inode; }
        public void setInode(long inode) { this.inode = inode; }

        public long getOffset() { return offset; }
        public void setOffset(long offset) { this.offset = offset; }

        public long getLastModified() { return lastModified; }
        public void setLastModified(long lastModified) { this.lastModified = lastModified; }

        public long getLastReadTime() { return lastReadTime; }
        public void setLastReadTime(long lastReadTime) { this.lastReadTime = lastReadTime; }
    }

    public OffsetManager(String storePath) {
        this.storePath = storePath;
        this.offsets = new ConcurrentHashMap<>();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "offset-persister");
            t.setDaemon(true);
            return t;
        });
        this.dirty = false;
        loadOffsets();
        startPersistence();
    }

    private void loadOffsets() {
        Path path = Paths.get(storePath);
        if (!Files.exists(path)) {
            log.info("No offset store found at {}, starting fresh", storePath);
            return;
        }

        try {
            String content = Files.readString(path);
            if (!content.isBlank()) {
                Map<String, FileOffset> loaded = JsonUtils.fromJson(content, new TypeReference<Map<String, FileOffset>>() {});
                offsets.putAll(loaded);
                log.info("Loaded {} offsets from {}", offsets.size(), storePath);
            }
        } catch (IOException e) {
            log.error("Failed to load offsets from {}", storePath, e);
        }
    }

    private void startPersistence() {
        scheduler.scheduleAtFixedRate(() -> {
            if (dirty) {
                persistOffsets();
                dirty = false;
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private synchronized void persistOffsets() {
        try {
            Path path = Paths.get(storePath);
            Files.createDirectories(path.getParent());
            String json = JsonUtils.toJson(offsets);
            try (FileChannel channel = FileChannel.open(path,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(json.getBytes());
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            log.debug("Persisted {} offsets", offsets.size());
        } catch (IOException e) {
            log.error("Failed to persist offsets", e);
        }
    }

    public long getOffset(String filePath, long inode, long fileSize) {
        FileOffset offset = offsets.get(filePath);
        if (offset == null) {
            return 0;
        }

        if (offset.getInode() != inode) {
            log.info("File inode changed for {}, starting from beginning", filePath);
            return 0;
        }

        if (offset.getOffset() > fileSize) {
            log.info("File truncated for {}, starting from beginning", filePath);
            return 0;
        }

        return offset.getOffset();
    }

    public void updateOffset(String filePath, long inode, long offset, long lastModified) {
        FileOffset fileOffset = new FileOffset(filePath, inode, offset, lastModified);
        offsets.put(filePath, fileOffset);
        dirty = true;
    }

    public void removeOffset(String filePath) {
        offsets.remove(filePath);
        dirty = true;
    }

    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        persistOffsets();
    }
}
