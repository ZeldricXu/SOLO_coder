package com.loganalytics.agent.multiline;

import com.loganalytics.agent.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class MultiLineMerger {
    private static final Logger log = LoggerFactory.getLogger(MultiLineMerger.class);

    private final Pattern pattern;
    private final boolean negate;
    private final String match;
    private final Duration flushTimeout;
    private final int maxLines;
    private final StringBuilder buffer;
    private int lineCount;
    private long lastLineTime;
    private volatile boolean hasPending;

    public interface LineHandler {
        void onCompleteLine(String mergedLine, int lineCount);
    }

    public MultiLineMerger(AgentConfig config) {
        this.pattern = Pattern.compile(config.getMultiLinePattern());
        this.negate = config.isMultiLineNegate();
        this.match = config.getMultiLineMatch();
        this.flushTimeout = Duration.ofSeconds(5);
        this.maxLines = 1000;
        this.buffer = new StringBuilder();
        this.lineCount = 0;
        this.lastLineTime = 0;
        this.hasPending = false;
    }

    public void processLine(String line, LineHandler handler) {
        boolean isNewEvent = isNewEvent(line);

        if (isNewEvent && hasPending) {
            flush(handler);
        }

        if (buffer.length() > 0) {
            buffer.append('\n');
        }
        buffer.append(line);
        lineCount++;
        lastLineTime = System.currentTimeMillis();
        hasPending = true;

        if (lineCount >= maxLines) {
            log.warn("Multi-line buffer reached max lines {}, flushing", maxLines);
            flush(handler);
        }
    }

    private boolean isNewEvent(String line) {
        boolean matches = pattern.matcher(line).find();
        return negate != matches;
    }

    public void checkTimeout(LineHandler handler) {
        if (hasPending && System.currentTimeMillis() - lastLineTime > flushTimeout.toMillis()) {
            log.debug("Flushing multi-line buffer due to timeout");
            flush(handler);
        }
    }

    public void flush(LineHandler handler) {
        if (buffer.length() > 0) {
            handler.onCompleteLine(buffer.toString(), lineCount);
            buffer.setLength(0);
            lineCount = 0;
            hasPending = false;
        }
    }

    public boolean hasPending() {
        return hasPending;
    }
}
