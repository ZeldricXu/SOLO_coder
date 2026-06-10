package com.loganalytics.agent.input;

import com.loganalytics.agent.config.AgentConfig;
import com.loganalytics.agent.multiline.MultiLineMerger;
import com.loganalytics.agent.offset.OffsetManager;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.test.builder.LogEventBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FileTailerTest {

    @TempDir
    Path tempDir;

    private Path testLogFile;
    private Path offsetStorePath;
    private AgentConfig config;
    private List<LogEvent> receivedEvents;
    private FileTailer.LogEventHandler eventHandler;

    @BeforeEach
    void setUp() throws IOException {
        testLogFile = tempDir.resolve("test.log");
        Files.createFile(testLogFile);

        offsetStorePath = tempDir.resolve("offsets.json");

        config = new AgentConfig();
        config.setServiceName("test-service");
        config.setHostname("test-host");
        config.setSourceIp("127.0.0.1");
        config.setMaxLineBytes(1024 * 1024);
        config.setMultiLineEnabled(false);

        receivedEvents = new ArrayList<>();
        eventHandler = receivedEvents::add;
    }

    @AfterEach
    void tearDown() {
        receivedEvents.clear();
    }

    @Test
    void shouldReadNewLinesAppendedToFile() throws Exception {
        List<String> testLines = generateLogLines(10, LogLevel.INFO);
        writeLines(testLines);

        OffsetManager offsetManager = new OffsetManager(offsetStorePath.toString());
        MultiLineMerger multiLineMerger = new MultiLineMerger(config);
        FileTailer tailer = new FileTailer(config, testLogFile.toString(), offsetManager, multiLineMerger, eventHandler);

        tailer.tail();

        assertThat(receivedEvents).hasSize(10);
        for (int i = 0; i < 10; i++) {
            LogEvent event = receivedEvents.get(i);
            assertThat(event.getMessage()).isEqualTo(testLines.get(i));
            assertThat(event.getServiceName()).isEqualTo("test-service");
            assertThat(event.getHostname()).isEqualTo("test-host");
            assertThat(event.getSource()).isEqualTo("file");
            assertThat(event.getFilePath()).isEqualTo(testLogFile.toString());
        }

        assertThat(receivedEvents.get(9).getFileOffset()).isGreaterThan(0);

        offsetManager.close();
    }

    @Test
    void shouldReadFromEndOfFileOnFirstStart() throws Exception {
        List<String> existingLines = generateLogLines(5, LogLevel.INFO);
        writeLines(existingLines);

        OffsetManager offsetManager = new OffsetManager(offsetStorePath.toString());
        MultiLineMerger multiLineMerger = new MultiLineMerger(config);
        FileTailer tailer = new FileTailer(config, testLogFile.toString(), offsetManager, multiLineMerger, eventHandler);

        tailer.tail();

        assertThat(receivedEvents).hasSize(5);

        List<String> newLines = generateLogLines(3, LogLevel.INFO);
        appendLines(newLines);

        tailer.tail();

        assertThat(receivedEvents).hasSize(8);
        for (int i = 5; i < 8; i++) {
            assertThat(receivedEvents.get(i).getMessage()).isEqualTo(newLines.get(i - 5));
        }

        offsetManager.close();
    }

    @Test
    void shouldResumeFromLastOffsetAfterRestart_NoDuplicatesNoMissing() throws Exception {
        List<String> firstBatch = generateLogLines(5, LogLevel.INFO);
        writeLines(firstBatch);

        OffsetManager offsetManager1 = new OffsetManager(offsetStorePath.toString());
        MultiLineMerger multiLineMerger1 = new MultiLineMerger(config);
        FileTailer tailer1 = new FileTailer(config, testLogFile.toString(), offsetManager1, multiLineMerger1, eventHandler);

        tailer1.tail();
        assertThat(receivedEvents).hasSize(5);
        offsetManager1.close();

        List<String> secondBatch = generateLogLines(5, LogLevel.INFO);
        appendLines(secondBatch);

        receivedEvents.clear();

        OffsetManager offsetManager2 = new OffsetManager(offsetStorePath.toString());
        MultiLineMerger multiLineMerger2 = new MultiLineMerger(config);
        FileTailer tailer2 = new FileTailer(config, testLogFile.toString(), offsetManager2, multiLineMerger2, eventHandler);

        tailer2.tail();

        assertThat(receivedEvents).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(receivedEvents.get(i).getMessage()).isEqualTo(secondBatch.get(i));
        }

        for (LogEvent event : receivedEvents) {
            assertThat(event.getMessage()).isNotIn(firstBatch);
        }

        offsetManager2.close();
    }

    @Test
    void shouldHandleFileRotationCorrectly() throws Exception {
        List<String> originalLines = generateLogLines(3, LogLevel.INFO);
        writeLines(originalLines);

        OffsetManager offsetManager = new OffsetManager(offsetStorePath.toString());
        MultiLineMerger multiLineMerger = new MultiLineMerger(config);
        FileTailer tailer = new FileTailer(config, testLogFile.toString(), offsetManager, multiLineMerger, eventHandler);

        tailer.tail();
        assertThat(receivedEvents).hasSize(3);

        Files.delete(testLogFile);
        Thread.sleep(100);

        List<String> newFileLines = generateLogLines(3, LogLevel.INFO);
        writeLines(newFileLines);

        tailer.tail();

        assertThat(receivedEvents).hasSize(6);
        for (int i = 3; i < 6; i++) {
            assertThat(receivedEvents.get(i).getMessage()).isEqualTo(newFileLines.get(i - 3));
        }

        offsetManager.close();
    }

    @Test
    void shouldHandleFileTruncation() throws Exception {
        List<String> originalLines = generateLogLines(5, LogLevel.INFO);
        writeLines(originalLines);

        OffsetManager offsetManager = new OffsetManager(offsetStorePath.toString());
        MultiLineMerger multiLineMerger = new MultiLineMerger(config);
        FileTailer tailer = new FileTailer(config, testLogFile.toString(), offsetManager, multiLineMerger, eventHandler);

        tailer.tail();
        assertThat(receivedEvents).hasSize(5);

        Files.writeString(testLogFile, "");

        List<String> newLines = generateLogLines(2, LogLevel.INFO);
        appendLines(newLines);

        tailer.tail();

        assertThat(receivedEvents).hasSize(7);
        for (int i = 5; i < 7; i++) {
            assertThat(receivedEvents.get(i).getMessage()).isEqualTo(newLines.get(i - 5));
        }

        offsetManager.close();
    }

    @Test
    void shouldExtractTimestampAndLevelCorrectly() throws Exception {
        String ts = Instant.now().toString().substring(0, 23) + "Z";
        String errorLine = ts + " ERROR payment-service - Database connection failed";
        String warnLine = ts + " WARN  payment-service - High memory usage detected";
        String infoLine = ts + " INFO  payment-service - Request processed in 45ms";

        writeLines(List.of(errorLine, warnLine, infoLine));

        OffsetManager offsetManager = new OffsetManager(offsetStorePath.toString());
        MultiLineMerger multiLineMerger = new MultiLineMerger(config);
        FileTailer tailer = new FileTailer(config, testLogFile.toString(), offsetManager, multiLineMerger, eventHandler);

        tailer.tail();

        assertThat(receivedEvents).hasSize(3);
        assertThat(receivedEvents.get(0).getLevel()).isEqualTo(LogLevel.ERROR);
        assertThat(receivedEvents.get(1).getLevel()).isEqualTo(LogLevel.WARN);
        assertThat(receivedEvents.get(2).getLevel()).isEqualTo(LogLevel.INFO);
        assertThat(receivedEvents.get(0).getTimestamp()).isNotNull();

        offsetManager.close();
    }

    @Test
    void shouldNotReadEmptyLines() throws Exception {
        writeLines(List.of(
                "2024-01-15T10:00:00Z INFO service - Line 1",
                "",
                "2024-01-15T10:00:01Z INFO service - Line 2",
                "   ",
                "2024-01-15T10:00:02Z INFO service - Line 3"
        ));

        OffsetManager offsetManager = new OffsetManager(offsetStorePath.toString());
        MultiLineMerger multiLineMerger = new MultiLineMerger(config);
        FileTailer tailer = new FileTailer(config, testLogFile.toString(), offsetManager, multiLineMerger, eventHandler);

        tailer.tail();

        assertThat(receivedEvents).hasSize(3);

        offsetManager.close();
    }

    private List<String> generateLogLines(int count, LogLevel level) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String ts = Instant.now().toString();
            lines.add(String.format("%s %s test-service - Log line %d: %s",
                    ts, level.name(), i, LogEventBuilder.aLogEvent().build().getMessage()));
        }
        return lines;
    }

    private void writeLines(List<String> lines) throws IOException {
        Files.write(testLogFile, lines);
    }

    private void appendLines(List<String> lines) throws IOException {
        List<String> allLines = new ArrayList<>(Files.readAllLines(testLogFile));
        allLines.addAll(lines);
        Files.write(testLogFile, allLines);
    }
}
