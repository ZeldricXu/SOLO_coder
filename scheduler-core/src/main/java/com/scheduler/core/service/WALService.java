package com.scheduler.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class WALService {

    private final ObjectMapper objectMapper;

    @Value("${wal.path:./data/wal}")
    private String walPath;

    @Value("${wal.rotation.size:10485760}")
    private long rotationSize;

    @Value("${wal.retention.hours:24}")
    private int retentionHours;

    private Path currentLogFile;
    private BufferedWriter writer;
    private final AtomicLong entriesWritten = new AtomicLong(0);
    private final List<WALEntry> recoveredEntries = new ArrayList<>();

    public record WALEntry(
            long sequence,
            String operation,
            String entityType,
            String entityId,
            Map<String, Object> payload,
            Instant timestamp
    ) implements Serializable {}

    @PostConstruct
    public void init() throws IOException {
        Path walDir = Paths.get(walPath);
        Files.createDirectories(walDir);

        recover();

        rotateLogFile();

        log.info("WAL service initialized at: {}", walPath);
    }

    @PreDestroy
    public void shutdown() throws IOException {
        if (writer != null) {
            writer.flush();
            writer.close();
        }
        log.info("WAL service shutdown complete, {} entries written", entriesWritten.get());
    }

    public synchronized void write(String operation, String entityType, String entityId, Map<String, Object> payload) {
        try {
            WALEntry entry = new WALEntry(
                    entriesWritten.incrementAndGet(),
                    operation,
                    entityType,
                    entityId,
                    payload,
                    Instant.now()
            );

            String line = objectMapper.writeValueAsString(entry);
            writer.write(line);
            writer.newLine();
            writer.flush();

            checkRotation();

            if (entriesWritten.get() % 1000 == 0) {
                log.debug("WAL entries written: {}", entriesWritten.get());
            }
        } catch (IOException e) {
            log.error("Failed to write WAL entry", e);
            throw new RuntimeException("WAL write failed", e);
        }
    }

    private void checkRotation() throws IOException {
        if (Files.size(currentLogFile) >= rotationSize) {
            rotateLogFile();
            cleanupOldLogs();
        }
    }

    private void rotateLogFile() throws IOException {
        if (writer != null) {
            writer.flush();
            writer.close();
        }

        String fileName = "wal_" + System.currentTimeMillis() + ".log";
        currentLogFile = Paths.get(walPath, fileName);
        writer = Files.newBufferedWriter(currentLogFile, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        log.info("Rotated WAL log file: {}", currentLogFile);
    }

    private void recover() throws IOException {
        Path walDir = Paths.get(walPath);
        if (!Files.exists(walDir)) {
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(walDir, "*.log")) {
            for (Path file : stream) {
                try (BufferedReader reader = Files.newBufferedReader(file)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        try {
                            WALEntry entry = objectMapper.readValue(line, WALEntry.class);
                            recoveredEntries.add(entry);
                        } catch (Exception e) {
                            log.warn("Failed to parse WAL entry: {}", line);
                        }
                    }
                }
            }
        }

        if (!recoveredEntries.isEmpty()) {
            log.info("Recovered {} WAL entries from previous run", recoveredEntries.size());
        }
    }

    private void cleanupOldLogs() throws IOException {
        Path walDir = Paths.get(walPath);
        long cutoffTime = System.currentTimeMillis() - (retentionHours * 3600000L);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(walDir, "*.log")) {
            for (Path file : stream) {
                if (Files.getLastModifiedTime(file).toMillis() < cutoffTime) {
                    compressAndDelete(file);
                }
            }
        }
    }

    private void compressAndDelete(Path file) throws IOException {
        Path compressed = Paths.get(file.toString() + ".gz");
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(compressed));
             BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                gzip.write(line.getBytes());
                gzip.write('\n');
            }
        }
        Files.delete(file);
        log.debug("Compressed and deleted old WAL file: {}", file);
    }

    public List<WALEntry> getRecoveredEntries() {
        return new ArrayList<>(recoveredEntries);
    }

    public long getEntriesWritten() {
        return entriesWritten.get();
    }

    public Path getCurrentLogFile() {
        return currentLogFile;
    }
}
