package com.cicd.server.websocket;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LogWebSocketRateLimitTest {

    private SimpMessagingTemplate messagingTemplate;
    private LogWebSocketService logService;
    private AtomicInteger messageCount;

    @BeforeEach
    void setUp() {
        messagingTemplate = mock(SimpMessagingTemplate.class);
        messageCount = new AtomicInteger(0);
        doAnswer(inv -> {
            messageCount.incrementAndGet();
            return null;
        }).when(messagingTemplate).convertAndSend(anyString(), any());
        logService = new LogWebSocketService(messagingTemplate);
        logService.init();
    }

    @AfterEach
    void tearDown() {
        if (logService != null) {
            logService.destroy();
        }
    }

    @Test
    void testRateLimitingBatchesExcessMessages() throws InterruptedException {
        long jobId = 1L;
        long stepId = 10L;
        int totalLines = 1000;

        for (int i = 0; i < totalLines; i++) {
            logService.broadcastLog(jobId, stepId, "log line " + i);
        }

        Thread.sleep(500);
        logService.destroy();
        logService = null;

        assertTrue(messageCount.get() < totalLines,
            "Should batch messages: " + messageCount.get() + " < " + totalLines);
        assertTrue(messageCount.get() > 0, "Should send some messages");
    }

    @Test
    void testLineNumberContinuity() throws InterruptedException {
        long jobId = 2L;
        long stepId = 20L;
        int lines = 50;

        CopyOnWriteArrayList<Long> allLineNumbers = new CopyOnWriteArrayList<>();
        messageCount.set(0);
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            var msg = (java.util.Map<String, Object>) inv.getArgument(1);
            @SuppressWarnings("unchecked")
            var lineNums = (java.util.List<Long>) msg.get("lineNumbers");
            if (lineNums != null) {
                allLineNumbers.addAll(lineNums);
            }
            messageCount.incrementAndGet();
            return null;
        }).when(messagingTemplate).convertAndSend(anyString(), any());

        logService = new LogWebSocketService(messagingTemplate);
        logService.init();

        for (int i = 0; i < lines; i++) {
            logService.broadcastLog(jobId, stepId, "line " + i);
        }

        Thread.sleep(300);
        logService.destroy();
        logService = null;

        assertEquals(lines, allLineNumbers.size(), "All line numbers should be delivered");
        for (int i = 0; i < lines; i++) {
            assertEquals(i + 1L, allLineNumbers.get(i),
                "Line numbers should be contiguous in order at index " + i);
        }
    }

    @Test
    void testBatchFlagSetCorrectly() throws InterruptedException {
        long jobId = 3L;
        long stepId = 30L;
        CopyOnWriteArrayList<Boolean> batchFlags = new CopyOnWriteArrayList<>();

        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            var msg = (java.util.Map<String, Object>) inv.getArgument(1);
            batchFlags.add((Boolean) msg.get("isBatch"));
            return null;
        }).when(messagingTemplate).convertAndSend(anyString(), any());

        logService = new LogWebSocketService(messagingTemplate);
        logService.init();

        for (int i = 0; i < 200; i++) {
            logService.broadcastLog(jobId, stepId, "line " + i);
        }

        Thread.sleep(300);
        logService.destroy();
        logService = null;

        assertTrue(batchFlags.stream().anyMatch(Boolean::booleanValue),
            "Some messages should be batched");
    }

    @Test
    void testHighVolumePerformance() throws InterruptedException {
        long startTime = System.currentTimeMillis();
        int totalLines = 10000;

        for (int i = 0; i < totalLines; i++) {
            logService.broadcastLog((long) (i % 10), (long) i, "log line " + i);
        }

        Thread.sleep(1000);
        logService.destroy();
        logService = null;

        long duration = System.currentTimeMillis() - startTime;
        assertTrue(duration < 5000, "Should handle 10k lines quickly: " + duration + "ms");
    }

    @Test
    void testBatchSizeTracking() throws InterruptedException {
        long jobId = 4L;
        long stepId = 40L;
        CopyOnWriteArrayList<Integer> batchSizes = new CopyOnWriteArrayList<>();

        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            var msg = (java.util.Map<String, Object>) inv.getArgument(1);
            batchSizes.add((Integer) msg.get("batchSize"));
            return null;
        }).when(messagingTemplate).convertAndSend(anyString(), any());

        logService = new LogWebSocketService(messagingTemplate);
        logService.init();

        for (int i = 0; i < 150; i++) {
            logService.broadcastLog(jobId, stepId, "line " + i);
        }

        Thread.sleep(300);
        logService.destroy();
        logService = null;

        int totalDelivered = batchSizes.stream().mapToInt(Integer::intValue).sum();
        assertEquals(150, totalDelivered, "All lines should be delivered in batches");
    }

    @Test
    void testMultipleJobsIndependentBuffering() throws InterruptedException {
        long jobId1 = 5L;
        long jobId2 = 6L;
        long stepId = 50L;

        CopyOnWriteArrayList<Long> jobIdsInMessages = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            var msg = (java.util.Map<String, Object>) inv.getArgument(1);
            jobIdsInMessages.add((Long) msg.get("jobId"));
            return null;
        }).when(messagingTemplate).convertAndSend(anyString(), any());

        logService = new LogWebSocketService(messagingTemplate);
        logService.init();

        for (int i = 0; i < 50; i++) {
            logService.broadcastLog(jobId1, stepId, "job1 line " + i);
            logService.broadcastLog(jobId2, stepId, "job2 line " + i);
        }

        Thread.sleep(300);
        logService.destroy();
        logService = null;

        assertTrue(jobIdsInMessages.contains(jobId1), "Should have messages for job 1");
        assertTrue(jobIdsInMessages.contains(jobId2), "Should have messages for job 2");
    }

    @Test
    void testClearLogBufferRemovesJobData() {
        long jobId = 7L;
        long stepId = 60L;

        logService.broadcastLog(jobId, stepId, "test line");
        logService.clearLogBuffer(jobId);

        String remaining = logService.getLogBuffer(jobId);
        assertEquals("", remaining, "Log buffer should be empty after clear");
    }
}
