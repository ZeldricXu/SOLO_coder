package com.datastandard.modules.profiling;

import cn.hutool.core.util.StrUtil;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class AsyncProfilerBridge {

    @Value("${profiling.async-profiler.home:}")
    private String asyncProfilerHome;

    @Value("${profiling.async-profiler.enabled:true}")
    private boolean enabled;

    @Value("${profiling.output.directory:/tmp/profiling}")
    private String outputDirectory;

    public boolean isAvailable() {
        if (!enabled) {
            return false;
        }
        try {
            Process process = Runtime.getRuntime().exec(getProfilerScript() + " --version");
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            log.debug("AsyncProfiler not available: {}", e.getMessage());
            return false;
        }
    }

    public ProfilerResult startProfiling(String sessionId, String pid, Duration duration,
                                         boolean cpu, boolean memory, boolean lock,
                                         boolean allocation, int intervalMs,
                                         List<String> includedPackages,
                                         List<String> excludedPackages) throws IOException {
        if (!isAvailable()) {
            throw new IllegalStateException("AsyncProfiler is not available");
        }

        ensureOutputDirectory();

        String outputFile = Paths.get(outputDirectory, sessionId + ".jfr").toString();

        List<String> args = new ArrayList<>();
        args.add(getProfilerScript());
        args.add("-e");

        List<String> events = new ArrayList<>();
        if (cpu) events.add("cpu");
        if (memory) events.add("alloc");
        if (lock) events.add("lock");
        if (allocation) events.add("alloc");
        args.add(String.join(",", events));

        args.add("-i");
        args.add(intervalMs + "ms");
        args.add("-d");
        args.add(String.valueOf(duration.getSeconds()));
        args.add("-o");
        args.add("jfr");
        args.add("-f");
        args.add(outputFile);

        if (includedPackages != null && !includedPackages.isEmpty()) {
            args.add("--include");
            args.add(String.join(",", includedPackages));
        }
        if (excludedPackages != null && !excludedPackages.isEmpty()) {
            args.add("--exclude");
            args.add(String.join(",", excludedPackages));
        }

        args.add(pid);

        log.info("Starting AsyncProfiler: {}", String.join(" ", args));
        Process process = new ProcessBuilder(args).inheritIO().start();

        return new ProfilerResult(process, outputFile, sessionId);
    }

    public ProfilerResult runProfiling(String sessionId, String pid, Duration duration,
                                       boolean cpu, boolean memory, boolean lock,
                                       boolean allocation, int intervalMs,
                                       List<String> includedPackages,
                                       List<String> excludedPackages)
            throws IOException, InterruptedException {
        if (!isAvailable()) {
            throw new IllegalStateException("AsyncProfiler is not available");
        }

        ensureOutputDirectory();

        String outputFile = Paths.get(outputDirectory, sessionId + ".jfr").toString();
        String flameGraphFile = Paths.get(outputDirectory, sessionId + "_flame.svg").toString();

        List<String> args = new ArrayList<>();
        args.add(getProfilerScript());
        args.add("-e");

        List<String> events = new ArrayList<>();
        if (cpu) events.add("cpu");
        if (memory) events.add("alloc");
        if (lock) events.add("lock");
        if (allocation) events.add("alloc");
        args.add(String.join(",", events));

        args.add("-i");
        args.add(intervalMs + "ms");
        args.add("-d");
        args.add(String.valueOf(duration.getSeconds()));
        args.add("-o");
        args.add("jfr");
        args.add("-f");
        args.add(outputFile);

        if (includedPackages != null && !includedPackages.isEmpty()) {
            args.add("--include");
            args.add(String.join(",", includedPackages));
        }
        if (excludedPackages != null && !excludedPackages.isEmpty()) {
            args.add("--exclude");
            args.add(String.join(",", excludedPackages));
        }

        args.add(pid);

        log.info("Running AsyncProfiler: {}", String.join(" ", args));
        Process process = new ProcessBuilder(args).inheritIO().start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            throw new IOException("AsyncProfiler exited with code " + exitCode);
        }

        generateFlameGraph(outputFile, flameGraphFile, cpu ? "cpu" : "alloc");

        return new ProfilerResult(process, outputFile, flameGraphFile, sessionId);
    }

    public void generateFlameGraph(String jfrFile, String outputFile, String type) throws IOException {
        if (!isAvailable()) {
            return;
        }

        List<String> args = new ArrayList<>();
        args.add(getProfilerScript());
        args.add("-e");
        args.add(type);
        args.add("-o");
        args.add("svg");
        args.add("-f");
        args.add(outputFile);
        args.add("--reverse");
        args.add(jfrFile);

        Process process = new ProcessBuilder(args).inheritIO().start();
        try {
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.warn("Flame graph generation exited with code {}", exitCode);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while generating flame graph", e);
        }
    }

    public String readFlameGraphSvg(String flameGraphFile) throws IOException {
        Path path = Paths.get(flameGraphFile);
        if (Files.exists(path)) {
            return Files.readString(path);
        }
        return null;
    }

    public Optional<String> getCurrentJvmPid() {
        try {
            String jvmName = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            return Optional.of(jvmName.split("@")[0]);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    public String getJvmVersion() {
        return System.getProperty("java.version");
    }

    private String getProfilerScript() {
        if (StrUtil.isNotBlank(asyncProfilerHome)) {
            return Paths.get(asyncProfilerHome, "bin", "asprof").toString();
        }
        return "asprof";
    }

    private void ensureOutputDirectory() throws IOException {
        Path path = Paths.get(outputDirectory);
        if (!Files.exists(path)) {
            Files.createDirectories(path);
        }
    }

    @Data
    public static class ProfilerResult {
        private final Process process;
        private final String jfrOutputFile;
        private String flameGraphFile;
        private final String sessionId;
        private boolean completed;

        public ProfilerResult(Process process, String jfrOutputFile, String sessionId) {
            this.process = process;
            this.jfrOutputFile = jfrOutputFile;
            this.sessionId = sessionId;
        }

        public ProfilerResult(Process process, String jfrOutputFile, String flameGraphFile, String sessionId) {
            this.process = process;
            this.jfrOutputFile = jfrOutputFile;
            this.flameGraphFile = flameGraphFile;
            this.sessionId = sessionId;
            this.completed = true;
        }

        public boolean isRunning() {
            return process.isAlive();
        }

        public void stop() {
            process.destroy();
        }
    }
}
