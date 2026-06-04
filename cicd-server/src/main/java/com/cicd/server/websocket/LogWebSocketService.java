package com.cicd.server.websocket;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogWebSocketService {

    private static final int MAX_LINES_PER_SECOND = 100;
    private static final long BUFFER_FLUSH_INTERVAL_MS = 100;

    private final SimpMessagingTemplate messagingTemplate;

    private final ConcurrentHashMap<Long, LogBuffer> jobBuffers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @PostConstruct
    public void init() {
        scheduler.scheduleAtFixedRate(
            this::flushAllBuffers,
            BUFFER_FLUSH_INTERVAL_MS,
            BUFFER_FLUSH_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
        log.info("LogWebSocketService rate limiter initialized: {} lines/sec", MAX_LINES_PER_SECOND);
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
        flushAllBuffers();
        log.info("LogWebSocketService stopped");
    }

    public void broadcastLog(Long jobId, Long stepId, String logLine) {
        LogBuffer buffer = jobBuffers.computeIfAbsent(jobId, k -> new LogBuffer());
        buffer.addLine(stepId, logLine);
        if (!buffer.isThrottled()) {
            flushSingleBuffer(jobId, buffer, false);
        }
    }

    private void flushAllBuffers() {
        for (var entry : jobBuffers.entrySet()) {
            flushSingleBuffer(entry.getKey(), entry.getValue(), true);
        }
    }

    private void flushSingleBuffer(Long jobId, LogBuffer buffer, boolean fromScheduler) {
        if (buffer.isEmpty()) return;

        BufferedLines batch = buffer.drain();
        if (batch.lines().isEmpty()) return;

        try {
            var message = new java.util.HashMap<String, Object>();
            message.put("jobId", jobId);
            message.put("stepId", batch.stepId());
            message.put("lines", batch.lines());
            message.put("lineNumbers", batch.lineNumbers());
            message.put("isBatch", batch.lines().size() > 1);
            message.put("batchSize", batch.lines().size());
            message.put("timestamp", System.currentTimeMillis());
            message.put("fromScheduler", fromScheduler);

            messagingTemplate.convertAndSend("/topic/logs/" + jobId, message);
        } catch (Exception e) {
            log.error("Failed to broadcast log batch for job {}", jobId, e);
        }
    }

    public String getLogBuffer(Long jobId) {
        LogBuffer buffer = jobBuffers.get(jobId);
        return buffer != null ? buffer.getFullLog() : "";
    }

    public void clearLogBuffer(Long jobId) {
        jobBuffers.remove(jobId);
    }

    public void broadcastStatusUpdate(Long executionId, String status) {
        try {
            var message = new java.util.HashMap<String, Object>();
            message.put("executionId", executionId);
            message.put("status", status);
            message.put("timestamp", System.currentTimeMillis());
            messagingTemplate.convertAndSend("/topic/executions/" + executionId, message);
        } catch (Exception e) {
            log.error("Failed to broadcast status update for execution {}", executionId, e);
        }
    }

    private static class LogBuffer {
        private final AtomicLong lineCounter = new AtomicLong(0);
        private final java.util.concurrent.ConcurrentLinkedQueue<LogLine> buffer = new java.util.concurrent.ConcurrentLinkedQueue<>();
        private final java.util.concurrent.atomic.AtomicInteger linesThisSecond = new java.util.concurrent.atomic.AtomicInteger(0);
        private volatile long windowStart = System.currentTimeMillis();
        private volatile Long currentStepId;

        synchronized void addLine(Long stepId, String line) {
            long now = System.currentTimeMillis();
            if (now - windowStart > 1000) {
                windowStart = now;
                linesThisSecond.set(0);
            }
            linesThisSecond.incrementAndGet();
            currentStepId = stepId;
            buffer.add(new LogLine(stepId, lineCounter.incrementAndGet(), line));
        }

        boolean isThrottled() {
            return linesThisSecond.get() > MAX_LINES_PER_SECOND;
        }

        boolean isEmpty() {
            return buffer.isEmpty();
        }

        synchronized BufferedLines drain() {
            if (buffer.isEmpty()) return new BufferedLines(null, java.util.List.of(), java.util.List.of());

            Long stepId = currentStepId;
            var lines = new java.util.ArrayList<String>();
            var numbers = new java.util.ArrayList<Long>();

            LogLine entry;
            while ((entry = buffer.poll()) != null) {
                lines.add(entry.content());
                numbers.add(entry.lineNumber());
                stepId = entry.stepId();
            }

            return new BufferedLines(stepId, lines, numbers);
        }

        String getFullLog() {
            var sb = new StringBuilder();
            for (var entry : buffer) {
                sb.append(entry.content()).append("\n");
            }
            return sb.toString();
        }
    }

    private record LogLine(Long stepId, long lineNumber, String content) {}
    private record BufferedLines(Long stepId, java.util.List<String> lines, java.util.List<Long> lineNumbers) {}
}
