package com.loganalytics.agent.input;

import com.loganalytics.agent.config.AgentConfig;
import com.loganalytics.agent.multiline.MultiLineMerger;
import com.loganalytics.agent.offset.OffsetManager;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.common.util.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.StandardOpenOption;

public class FileTailer {
    private static final Logger log = LoggerFactory.getLogger(FileTailer.class);

    private final AgentConfig config;
    private final String filePath;
    private final OffsetManager offsetManager;
    private final MultiLineMerger multiLineMerger;
    private final LogEventHandler eventHandler;
    private long inode;
    private long currentOffset;
    private long fileSize;
    private long lastModified;
    private volatile boolean running;

    public interface LogEventHandler {
        void onEvent(LogEvent event);
    }

    public FileTailer(AgentConfig config, String filePath, OffsetManager offsetManager,
                      MultiLineMerger multiLineMerger, LogEventHandler eventHandler) throws IOException {
        this.config = config;
        this.filePath = filePath;
        this.offsetManager = offsetManager;
        this.multiLineMerger = multiLineMerger;
        this.eventHandler = eventHandler;
        this.running = true;

        Path path = Paths.get(filePath);
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        this.inode = attrs.fileKey() != null ? attrs.fileKey().hashCode() : path.toAbsolutePath().hashCode();
        this.fileSize = attrs.size();
        this.lastModified = attrs.lastModifiedTime().toMillis();
        this.currentOffset = offsetManager.getOffset(filePath, inode, fileSize);

        log.info("Starting file tailer for {} at offset {}", filePath, currentOffset);
    }

    public void tail() throws IOException, InterruptedException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            log.warn("File no longer exists: {}", filePath);
            running = false;
            return;
        }

        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
        long newInode = attrs.fileKey() != null ? attrs.fileKey().hashCode() : path.toAbsolutePath().hashCode();
        long newSize = attrs.size();
        long newLastModified = attrs.lastModifiedTime().toMillis();

        if (newInode != inode) {
            log.info("File rotated: {} (old inode: {}, new inode: {})", filePath, inode, newInode);
            multiLineMerger.flush(this::handleCompleteLine);
            inode = newInode;
            currentOffset = 0;
        }

        if (newSize < currentOffset) {
            log.info("File truncated: {} (old offset: {}, new size: {})", filePath, currentOffset, newSize);
            multiLineMerger.flush(this::handleCompleteLine);
            currentOffset = 0;
        }

        fileSize = newSize;
        lastModified = newLastModified;

        if (currentOffset < fileSize) {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
                channel.position(currentOffset);
                ByteBuffer buffer = ByteBuffer.allocate(8192);
                StringBuilder lineBuffer = new StringBuilder();

                int bytesRead;
                while ((bytesRead = channel.read(buffer)) > 0) {
                    buffer.flip();
                    for (int i = 0; i < bytesRead; i++) {
                        byte b = buffer.get(i);
                        if (b == '\n') {
                            processLine(lineBuffer.toString());
                            lineBuffer.setLength(0);
                        } else if (b == '\r') {
                        } else {
                            if (lineBuffer.length() < config.getMaxLineBytes()) {
                                lineBuffer.append((char) b);
                            }
                        }
                    }
                    currentOffset = channel.position();
                    buffer.clear();
                }

                if (lineBuffer.length() > 0) {
                    processLine(lineBuffer.toString());
                }
            }

            offsetManager.updateOffset(filePath, inode, currentOffset, lastModified);
        }

        if (multiLineMerger.hasPending()) {
            multiLineMerger.checkTimeout(this::handleCompleteLine);
        }
    }

    private void processLine(String line) {
        if (line.isBlank()) return;

        if (config.isMultiLineEnabled() && multiLineMerger != null) {
            multiLineMerger.processLine(line, this::handleCompleteLine);
        } else {
            handleCompleteLine(line, 1);
        }
    }

    private void handleCompleteLine(String line, int multiLineCount) {
        LogEvent event = new LogEvent();
        event.setTimestamp(TimeUtils.parseTimestamp(extractTimestamp(line)));
        event.setLevel(extractLevel(line));
        event.setServiceName(config.getServiceName());
        event.setHostname(config.getHostname());
        event.setSourceIp(config.getSourceIp());
        event.setRawMessage(line);
        event.setMessage(line);
        event.setSource("file");
        event.setFilePath(filePath);
        event.setFileOffset(currentOffset);
        event.setMultiLineCount(multiLineCount);
        event.setTraceId(extractTraceId(line));

        eventHandler.onEvent(event);
    }

    private String extractTimestamp(String line) {
        int firstSpace = line.indexOf(' ');
        if (firstSpace > 0) {
            String maybeTs = line.substring(0, firstSpace);
            if (maybeTs.matches("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}.*")) {
                return maybeTs;
            }
        }
        return null;
    }

    private LogLevel extractLevel(String line) {
        String upper = line.toUpperCase();
        if (upper.contains("ERROR")) return LogLevel.ERROR;
        if (upper.contains("WARN")) return LogLevel.WARN;
        if (upper.contains("INFO")) return LogLevel.INFO;
        if (upper.contains("DEBUG")) return LogLevel.DEBUG;
        if (upper.contains("TRACE")) return LogLevel.TRACE;
        if (upper.contains("FATAL")) return LogLevel.FATAL;
        return LogLevel.UNKNOWN;
    }

    private String extractTraceId(String line) {
        int idx = line.indexOf("traceId=");
        if (idx >= 0) {
            int start = idx + 8;
            int end = line.indexOf(' ', start);
            if (end < 0) end = line.length();
            return line.substring(start, end).trim();
        }
        return null;
    }

    public String getFilePath() {
        return filePath;
    }

    public boolean isRunning() {
        return running;
    }

    public void close() {
        running = false;
        if (multiLineMerger != null) {
            multiLineMerger.flush(this::handleCompleteLine);
        }
        offsetManager.updateOffset(filePath, inode, currentOffset, lastModified);
    }
}
