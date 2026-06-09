package com.loganalytics.agent.input;

import com.loganalytics.agent.config.AgentConfig;
import com.loganalytics.agent.multiline.MultiLineMerger;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.common.util.TimeUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

public class StdoutCapturer {
    private static final Logger log = LoggerFactory.getLogger(StdoutCapturer.class);

    private final AgentConfig config;
    private final MultiLineMerger multiLineMerger;
    private final FileTailer.LogEventHandler eventHandler;
    private Process targetProcess;
    private Thread captureThread;
    private volatile boolean running;
    private final AtomicLong lineCounter;

    public StdoutCapturer(AgentConfig config, MultiLineMerger multiLineMerger,
                          FileTailer.LogEventHandler eventHandler) {
        this.config = config;
        this.multiLineMerger = multiLineMerger;
        this.eventHandler = eventHandler;
        this.lineCounter = new AtomicLong(0);
    }

    public void start() throws IOException {
        if (!config.isStdoutCaptureEnabled()) {
            log.info("Stdout capture is disabled");
            return;
        }

        long pid = config.getStdoutPid();
        if (pid <= 0) {
            pid = getCurrentPid();
            log.info("Capturing stdout from current process PID: {}", pid);
        } else {
            log.info("Capturing stdout from process PID: {}", pid);
        }

        ProcessBuilder pb = new ProcessBuilder("tail", "-f", "/proc/" + pid + "/fd/1");
        pb.redirectErrorStream(true);
        targetProcess = pb.start();

        running = true;
        captureThread = new Thread(this::captureLoop, "stdout-capturer");
        captureThread.setDaemon(true);
        captureThread.start();

        log.info("Stdout capturer started");
    }

    private long getCurrentPid() {
        try {
            String jvmName = ManagementFactory.getRuntimeMXBean().getName();
            return Long.parseLong(jvmName.split("@")[0]);
        } catch (Exception e) {
            try {
                Field pidField = ProcessHandle.current().getClass().getDeclaredField("pid");
                pidField.setAccessible(true);
                return (long) pidField.get(ProcessHandle.current());
            } catch (Exception ex) {
                return ProcessHandle.current().pid();
            }
        }
    }

    private void captureLoop() {
        try (InputStream is = targetProcess.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {

            String line;
            while (running && (line = reader.readLine()) != null) {
                processLine(line);
            }
        } catch (IOException e) {
            if (running) {
                log.error("Stdout capture failed", e);
            }
        } finally {
            log.info("Stdout capturer stopped");
        }
    }

    private void processLine(String line) {
        if (line.isBlank()) return;

        lineCounter.incrementAndGet();

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
        event.setSource("stdout");
        event.setMultiLineCount(multiLineCount);
        event.setTraceId(extractTraceId(line));
        event.addTag("capture_source", "stdout");

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

    public long getLineCount() {
        return lineCounter.get();
    }

    public void stop() {
        running = false;
        if (targetProcess != null) {
            targetProcess.destroyForcibly();
        }
        if (captureThread != null) {
            captureThread.interrupt();
            try {
                captureThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (multiLineMerger != null) {
            multiLineMerger.flush(this::handleCompleteLine);
        }
    }
}
