package com.enterprise.gateway.logprocessor.benchmark;

import com.enterprise.gateway.logprocessor.detector.FastFeatureExtractor;
import com.enterprise.gateway.logprocessor.detector.FormatDetector;
import com.enterprise.gateway.logprocessor.detector.SingleBytePrefixFilter;
import com.enterprise.gateway.logprocessor.detector.TwoPhaseFormatDetector;
import com.enterprise.gateway.logprocessor.model.LogFormat;
import com.enterprise.gateway.logprocessor.parser.CsvLogParser;
import com.enterprise.gateway.logprocessor.parser.JsonLogParser;
import com.enterprise.gateway.logprocessor.parser.LogParser;
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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class FormatDetectionBenchmark {

    private FormatDetector naiveDetector;
    private TwoPhaseFormatDetector twoPhaseDetector;
    private List<String> testLines;

    @Setup(Level.Trial)
    public void setup() {
        List<LogParser> parsers = new ArrayList<>();
        parsers.add(new JsonLogParser());
        parsers.add(new NginxLogParser());
        parsers.add(new LogbackLogParser());
        parsers.add(new SyslogParser());
        parsers.add(new CsvLogParser());

        naiveDetector = new FormatDetector(parsers);

        FastFeatureExtractor featureExtractor = new FastFeatureExtractor();
        SingleBytePrefixFilter byteFilter = new SingleBytePrefixFilter(parsers);
        twoPhaseDetector = new TwoPhaseFormatDetector(featureExtractor, byteFilter);

        testLines = BenchmarkDataGenerator.generateMixedLogLines(1000);
    }

    @Benchmark
    public void baselineNaiveDetection(Blackhole bh) {
        for (String line : testLines) {
            LogFormat format = naiveDetector.detectFormat(line);
            bh.consume(format);
        }
    }

    @Benchmark
    public void optimizedTwoPhaseDetection(Blackhole bh) {
        for (String line : testLines) {
            LogFormat format = twoPhaseDetector.detectFormat(line);
            bh.consume(format);
        }
    }
}
