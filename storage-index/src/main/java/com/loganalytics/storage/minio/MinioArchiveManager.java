package com.loganalytics.storage.minio;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.util.JsonUtils;
import com.loganalytics.storage.config.StorageConfig;
import io.minio.*;
import io.minio.errors.*;
import io.minio.messages.Bucket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

public class MinioArchiveManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MinioArchiveManager.class);
    private static final DateTimeFormatter PATH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final StorageConfig config;
    private final MinioClient minioClient;
    private final Map<String, DailyArchive> dailyArchives;
    private final ExecutorService uploadThreadPool;
    private final ScheduledExecutorService flushScheduler;
    private final AtomicLong totalArchived = new AtomicLong(0);
    private final AtomicLong uploadFailures = new AtomicLong(0);
    private volatile boolean running;

    static class DailyArchive {
        final String datePath;
        final List<LogEvent> buffer;
        final Object lock = new Object();
        long lastFlushTime;
        long currentOffset;
        String currentObjectName;
        ByteArrayOutputStream currentBuffer;
        GZIPOutputStream currentGzip;

        DailyArchive(String datePath) {
            this.datePath = datePath;
            this.buffer = new ArrayList<>();
            this.lastFlushTime = System.currentTimeMillis();
            this.currentOffset = 0;
        }
    }

    public static class ArchiveResult {
        private final String objectName;
        private final long offset;
        private final int length;
        private final long recordCount;

        public ArchiveResult(String objectName, long offset, int length, long recordCount) {
            this.objectName = objectName;
            this.offset = offset;
            this.length = length;
            this.recordCount = recordCount;
        }

        public String getObjectName() { return objectName; }
        public long getOffset() { return offset; }
        public int getLength() { return length; }
        public long getRecordCount() { return recordCount; }
    }

    public MinioArchiveManager(StorageConfig config) throws Exception {
        this.config = config;
        this.minioClient = createClient();
        this.dailyArchives = new ConcurrentHashMap<>();
        this.uploadThreadPool = Executors.newFixedThreadPool(config.getMinioUploadThreads());
        this.flushScheduler = Executors.newSingleThreadScheduledExecutor();
        this.running = true;

        initializeBucket();
        startFlushScheduler();

        log.info("MinIO archive manager initialized: endpoint={}, bucket={}",
                config.getMinioEndpoint(), config.getMinioBucketName());
    }

    private MinioClient createClient() {
        return MinioClient.builder()
                .endpoint(config.getMinioEndpoint())
                .credentials(config.getMinioAccessKey(), config.getMinioSecretKey())
                .build();
    }

    private void initializeBucket() throws Exception {
        boolean bucketExists = minioClient.bucketExists(
                BucketExistsArgs.builder().bucket(config.getMinioBucketName()).build()
        );

        if (!bucketExists) {
            minioClient.makeBucket(
                    MakeBucketArgs.builder().bucket(config.getMinioBucketName()).build()
            );
            log.info("Created bucket: {}", config.getMinioBucketName());
        } else {
            log.info("Bucket already exists: {}", config.getMinioBucketName());
        }

        try {
            List<Bucket> buckets = minioClient.listBuckets();
            log.debug("Available buckets: {}", buckets.stream().map(Bucket::name).toList());
        } catch (Exception e) {
            log.warn("Failed to list buckets: {}", e.getMessage());
        }
    }

    private void startFlushScheduler() {
        flushScheduler.scheduleAtFixedRate(
                this::flushAll,
                config.getMinioUploadInterval().getSeconds(),
                config.getMinioUploadInterval().getSeconds(),
                TimeUnit.SECONDS
        );
        log.info("Started flush scheduler with interval: {}s", config.getMinioUploadInterval().getSeconds());
    }

    public ArchiveResult archive(LogEvent event) {
        if (!running || event == null || event.getMessage() == null) {
            return null;
        }

        Instant timestamp = event.getTimestamp() != null ? event.getTimestamp() : Instant.now();
        String datePath = PATH_FORMATTER.format(timestamp.atZone(ZoneId.systemDefault()));
        String objectName = datePath + "/" + generateHourFileName(timestamp);

        DailyArchive archive = dailyArchives.computeIfAbsent(
                datePath, k -> new DailyArchive(datePath)
        );

        synchronized (archive.lock) {
            String json = JsonUtils.toJson(event);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

            long offset = archive.currentOffset;
            int length = bytes.length;

            if (archive.currentBuffer == null) {
                archive.currentBuffer = new ByteArrayOutputStream();
                try {
                    archive.currentGzip = new GZIPOutputStream(archive.currentBuffer);
                } catch (IOException e) {
                    log.error("Failed to create gzip stream", e);
                    return null;
                }
                archive.currentObjectName = objectName;
                archive.currentOffset = 0;
            }

            try {
                archive.currentGzip.write(bytes);
                archive.currentGzip.write('\n');
            } catch (IOException e) {
                log.error("Failed to write to gzip buffer", e);
                return null;
            }

            archive.currentOffset += length + 1;
            archive.buffer.add(event);

            totalArchived.incrementAndGet();

            if (archive.buffer.size() >= config.getBatchSize()) {
                scheduleFlush(archive);
            }

            return new ArchiveResult(archive.currentObjectName, offset, length, 1);
        }
    }

    private String generateHourFileName(Instant timestamp) {
        ZonedDateTime zdt = timestamp.atZone(ZoneId.systemDefault());
        return String.format("logs-%02d.jsonl.gz", zdt.getHour());
    }

    private void scheduleFlush(DailyArchive archive) {
        uploadThreadPool.submit(() -> {
            try {
                flushDailyArchive(archive);
            } catch (Exception e) {
                uploadFailures.incrementAndGet();
                log.error("Failed to flush daily archive: {}", archive.datePath, e);
            }
        });
    }

    private void flushDailyArchive(DailyArchive archive) throws Exception {
        synchronized (archive.lock) {
            if (archive.currentBuffer == null || archive.currentBuffer.size() == 0) {
                return;
            }

            try {
                archive.currentGzip.finish();
            } catch (IOException e) {
                log.error("Failed to finish gzip stream", e);
            }

            byte[] data = archive.currentBuffer.toByteArray();
            String objectName = archive.currentObjectName;
            int recordCount = archive.buffer.size();

            try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
                minioClient.putObject(
                        PutObjectArgs.builder()
                                .bucket(config.getMinioBucketName())
                                .object(objectName)
                                .stream(bais, data.length, config.getMinioPartSize())
                                .contentType("application/gzip")
                                .build()
                );

                log.debug("Uploaded {} records to {}/{} ({} bytes)",
                        recordCount, config.getMinioBucketName(), objectName, data.length);
            } catch (MinioException | InvalidKeyException | NoSuchAlgorithmException | IOException e) {
                uploadFailures.incrementAndGet();
                throw new Exception("Failed to upload to MinIO", e);
            }

            archive.buffer.clear();
            archive.currentBuffer = null;
            archive.currentGzip = null;
            archive.currentOffset = 0;
            archive.lastFlushTime = System.currentTimeMillis();
        }
    }

    public void flushAll() {
        if (!running) return;

        long now = System.currentTimeMillis();
        long flushIntervalMs = config.getMinioUploadInterval().toMillis();

        for (DailyArchive archive : dailyArchives.values()) {
            synchronized (archive.lock) {
                if (archive.currentBuffer != null &&
                        archive.currentBuffer.size() > 0 &&
                        (now - archive.lastFlushTime) >= flushIntervalMs) {
                    scheduleFlush(archive);
                }
            }
        }

        cleanupOldArchives(now);
    }

    private void cleanupOldArchives(long now) {
        long expireMs = Duration.ofHours(48).toMillis();
        dailyArchives.entrySet().removeIf(entry -> {
            DailyArchive archive = entry.getValue();
            return (now - archive.lastFlushTime) > expireMs;
        });
    }

    public List<LogEvent> readRange(String objectName, long offset, int length) throws Exception {
        return readRange(objectName, offset, length, 1);
    }

    public List<LogEvent> readRange(String objectName, long offset, int length, int count) throws Exception {
        try (InputStream is = minioClient.getObject(
                GetObjectArgs.builder()
                        .bucket(config.getMinioBucketName())
                        .object(objectName)
                        .build()
        )) {
            byte[] buffer = new byte[length];
            long actualSkipped = is.skip(offset);
            if (actualSkipped != offset) {
                log.warn("Skip mismatch: expected {}, actual {}", offset, actualSkipped);
            }

            int bytesRead = is.read(buffer, 0, length);
            if (bytesRead <= 0) {
                return Collections.emptyList();
            }

            List<LogEvent> events = new ArrayList<>();
            String content = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
            String[] lines = content.split("\n");

            for (String line : lines) {
                if (line.isBlank()) continue;
                try {
                    LogEvent event = JsonUtils.fromJson(line, LogEvent.class);
                    events.add(event);
                    if (events.size() >= count) break;
                } catch (Exception e) {
                    log.warn("Failed to parse log line: {}", e.getMessage());
                }
            }

            return events;

        } catch (MinioException | InvalidKeyException | NoSuchAlgorithmException e) {
            throw new Exception("Failed to read from MinIO", e);
        }
    }

    public List<String> listObjects(String prefix) throws Exception {
        List<String> objects = new ArrayList<>();

        Iterable<Result<io.minio.messages.Item>> results = minioClient.listObjects(
                ListObjectsArgs.builder()
                        .bucket(config.getMinioBucketName())
                        .prefix(prefix)
                        .recursive(true)
                        .build()
        );

        for (Result<io.minio.messages.Item> result : results) {
            try {
                objects.add(result.get().objectName());
            } catch (Exception e) {
                log.warn("Failed to list object: {}", e.getMessage());
            }
        }

        return objects;
    }

    public List<String> listObjectsForDate(LocalDate date) {
        String prefix = PATH_FORMATTER.format(date) + "/";
        try {
            return listObjects(prefix);
        } catch (Exception e) {
            log.error("Failed to list objects for date: {}", date, e);
            return Collections.emptyList();
        }
    }

    @Override
    public void close() {
        running = false;
        log.info("Shutting down MinIO archive manager...");

        flushScheduler.shutdown();
        try {
            if (!flushScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                flushScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            flushScheduler.shutdownNow();
        }

        for (DailyArchive archive : dailyArchives.values()) {
            try {
                flushDailyArchive(archive);
            } catch (Exception e) {
                log.error("Failed to flush archive during shutdown: {}", archive.datePath, e);
            }
        }

        uploadThreadPool.shutdown();
        try {
            if (!uploadThreadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                uploadThreadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            uploadThreadPool.shutdownNow();
        }

        log.info("MinIO archive manager shutdown complete. Total archived: {}, failures: {}",
                totalArchived.get(), uploadFailures.get());
    }

    public Map<String, Object> getDiagnostics() {
        return Map.of(
                "totalArchived", totalArchived.get(),
                "uploadFailures", uploadFailures.get(),
                "activeDates", dailyArchives.size(),
                "bucket", config.getMinioBucketName(),
                "endpoint", config.getMinioEndpoint()
        );
    }

    public boolean isRunning() {
        return running;
    }
}
