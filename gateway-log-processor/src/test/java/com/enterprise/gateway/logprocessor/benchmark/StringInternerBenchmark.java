package com.enterprise.gateway.logprocessor.benchmark;

import com.enterprise.gateway.logprocessor.memory.StringInterner;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.List;
import java.util.concurrent.TimeUnit;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(1)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
public class StringInternerBenchmark {

    private StringInterner interner;
    private List<String> testStrings;

    @Setup(Level.Trial)
    public void setup() {
        interner = StringInterner.getInstance();
        interner.clear();
        interner.resetStatistics();
        testStrings = BenchmarkDataGenerator.generateInternTestStrings(1000);
    }

    @Benchmark
    @OperationsPerInvocation(1000)
    public void withoutInterner(Blackhole bh) {
        for (String s : testStrings) {
            String result = new String(s);
            bh.consume(result);
        }
    }

    @Benchmark
    @OperationsPerInvocation(1000)
    public void withInterner(Blackhole bh) {
        for (String s : testStrings) {
            String result = interner.intern(s);
            bh.consume(result);
        }
    }

    public static void main(String[] args) throws RunnerException {
        Options opts = new OptionsBuilder()
                .include(StringInternerBenchmark.class.getSimpleName())
                .addProfiler(GCProfiler.class)
                .build();
        new Runner(opts).run();
    }
}
