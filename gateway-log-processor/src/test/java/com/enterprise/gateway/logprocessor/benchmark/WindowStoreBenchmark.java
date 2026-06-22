package com.enterprise.gateway.logprocessor.benchmark;

import com.enterprise.gateway.logprocessor.aggregation.AggregationState;
import com.enterprise.gateway.logprocessor.aggregation.BTreeWindowStore;
import com.enterprise.gateway.logprocessor.aggregation.RingBufferWindowStore;
import com.enterprise.gateway.logprocessor.model.LogEntry;
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
import java.util.Random;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class WindowStoreBenchmark {

    private BTreeWindowStore bTreeStore;
    private RingBufferWindowStore ringBufferStore;
    private List<LogEntry> logEntries;
    private long[] randomTimestamps;

    @Setup(Level.Trial)
    public void setup() {
        long startTime = System.currentTimeMillis() - 3600000;
        long endTime = System.currentTimeMillis();

        bTreeStore = new BTreeWindowStore(1000);
        ringBufferStore = new RingBufferWindowStore(1000, 3600);

        logEntries = BenchmarkDataGenerator.generateLogEntries(10000, startTime, endTime);

        Random random = new Random(54321);
        randomTimestamps = new long[1000];
        for (int i = 0; i < 1000; i++) {
            randomTimestamps[i] = startTime + (long) (random.nextDouble() * (endTime - startTime));
        }
    }

    @Benchmark
    public void btreeAdd(Blackhole bh) {
        BTreeWindowStore store = new BTreeWindowStore(1000);
        for (LogEntry entry : logEntries) {
            store.add(entry.getTimestamp(), entry);
        }
        bh.consume(store);
    }

    @Benchmark
    public void ringBufferAdd(Blackhole bh) {
        RingBufferWindowStore store = new RingBufferWindowStore(1000, 3600);
        for (LogEntry entry : logEntries) {
            store.add(entry.getTimestamp(), entry);
        }
        bh.consume(store);
    }

    @Benchmark
    public void btreeGetRandom(Blackhole bh) {
        BTreeWindowStore store = new BTreeWindowStore(1000);
        for (LogEntry entry : logEntries) {
            store.add(entry.getTimestamp(), entry);
        }
        for (long timestamp : randomTimestamps) {
            AggregationState state = store.getWindow(timestamp);
            bh.consume(state);
        }
    }

    @Benchmark
    public void ringBufferGetRandom(Blackhole bh) {
        RingBufferWindowStore store = new RingBufferWindowStore(1000, 3600);
        for (LogEntry entry : logEntries) {
            store.add(entry.getTimestamp(), entry);
        }
        for (long timestamp : randomTimestamps) {
            AggregationState state = store.getWindow(timestamp);
            bh.consume(state);
        }
    }
}
