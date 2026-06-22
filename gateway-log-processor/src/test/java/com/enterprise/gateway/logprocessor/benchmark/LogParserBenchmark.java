package com.enterprise.gateway.logprocessor.benchmark;

import com.enterprise.gateway.logprocessor.model.LogEntry;
import com.enterprise.gateway.logprocessor.parser.CsvLogParser;
import com.enterprise.gateway.logprocessor.parser.JsonLogParser;
import com.enterprise.gateway.logprocessor.parser.LogbackLogParser;
import com.enterprise.gateway.logprocessor.parser.NginxLogParser;
import com.enterprise.gateway.logprocessor.parser.SyslogParser;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class LogParserBenchmark {

    private JsonLogParser jsonParser;
    private NginxLogParser nginxParser;
    private LogbackLogParser logbackParser;
    private SyslogParser syslogParser;
    private CsvLogParser csvParser;

    private String jsonLine;
    private String nginxLine;
    private String logbackLine;
    private String syslogLine;
    private String csvLine;

    @Setup(Level.Trial)
    public void setup() {
        jsonParser = new JsonLogParser();
        nginxParser = new NginxLogParser();
        logbackParser = new LogbackLogParser();
        syslogParser = new SyslogParser();
        csvParser = new CsvLogParser();

        List<String> mixedLines = BenchmarkDataGenerator.generateMixedLogLines(10);
        jsonLine = mixedLines.get(0);
        nginxLine = mixedLines.get(2);
        logbackLine = mixedLines.get(4);
        syslogLine = mixedLines.get(6);
        csvLine = mixedLines.get(8);
    }

    @Benchmark
    public void jsonParserTryParse(Blackhole bh) {
        LogEntry.LogEntryBuilder builder = LogEntry.builder();
        boolean result = jsonParser.tryParse(jsonLine, builder);
        bh.consume(result);
        bh.consume(builder);
    }

    @Benchmark
    public void nginxParserTryParse(Blackhole bh) {
        LogEntry.LogEntryBuilder builder = LogEntry.builder();
        boolean result = nginxParser.tryParse(nginxLine, builder);
        bh.consume(result);
        bh.consume(builder);
    }

    @Benchmark
    public void logbackParserTryParse(Blackhole bh) {
        LogEntry.LogEntryBuilder builder = LogEntry.builder();
        boolean result = logbackParser.tryParse(logbackLine, builder);
        bh.consume(result);
        bh.consume(builder);
    }

    @Benchmark
    public void syslogParserTryParse(Blackhole bh) {
        LogEntry.LogEntryBuilder builder = LogEntry.builder();
        boolean result = syslogParser.tryParse(syslogLine, builder);
        bh.consume(result);
        bh.consume(builder);
    }

    @Benchmark
    public void csvParserTryParse(Blackhole bh) {
        LogEntry.LogEntryBuilder builder = LogEntry.builder();
        boolean result = csvParser.tryParse(csvLine, builder);
        bh.consume(result);
        bh.consume(builder);
    }

    @Benchmark
    public void jsonParserParse(Blackhole bh) {
        LogEntry entry = jsonParser.parse(jsonLine);
        bh.consume(entry);
    }

    @Benchmark
    public void nginxParserParse(Blackhole bh) {
        LogEntry entry = nginxParser.parse(nginxLine);
        bh.consume(entry);
    }

    @Benchmark
    public void logbackParserParse(Blackhole bh) {
        LogEntry entry = logbackParser.parse(logbackLine);
        bh.consume(entry);
    }

    @Benchmark
    public void syslogParserParse(Blackhole bh) {
        LogEntry entry = syslogParser.parse(syslogLine);
        bh.consume(entry);
    }

    @Benchmark
    public void csvParserParse(Blackhole bh) {
        LogEntry entry = csvParser.parse(csvLine);
        bh.consume(entry);
    }
}
