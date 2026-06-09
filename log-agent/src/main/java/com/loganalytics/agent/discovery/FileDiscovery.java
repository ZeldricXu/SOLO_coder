package com.loganalytics.agent.discovery;

import com.loganalytics.agent.config.AgentConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FileDiscovery {
    private static final Logger log = LoggerFactory.getLogger(FileDiscovery.class);

    private final AgentConfig config;
    private final Set<String> discoveredFiles;
    private final Set<String> activeFiles;
    private final ScheduledExecutorService scheduler;
    private final List<PathMatcher> includeMatchers;
    private final List<PathMatcher> excludeMatchers;
    private final FileDiscoveryListener listener;

    public interface FileDiscoveryListener {
        void onFileDiscovered(String filePath);
        void onFileRemoved(String filePath);
    }

    public FileDiscovery(AgentConfig config, FileDiscoveryListener listener) {
        this.config = config;
        this.listener = listener;
        this.discoveredFiles = new CopyOnWriteArraySet<>();
        this.activeFiles = new CopyOnWriteArraySet<>();
        this.includeMatchers = config.getLogPaths().stream()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
        this.excludeMatchers = config.getExcludePatterns().stream()
                .map(pattern -> FileSystems.getDefault().getPathMatcher("glob:" + pattern))
                .toList();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "file-discovery");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        log.info("Starting file discovery with patterns: {}", config.getLogPaths());
        runDiscovery();
        scheduler.scheduleAtFixedRate(
                this::runDiscovery,
                config.getFileDiscoveryInterval().toMillis(),
                config.getFileDiscoveryInterval().toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private void runDiscovery() {
        Set<String> currentFiles = new HashSet<>();

        for (String pathPattern : config.getLogPaths()) {
            try {
                Path basePath = getBasePath(pathPattern);
                if (Files.exists(basePath)) {
                    Files.walkFileTree(basePath, new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (attrs.isRegularFile() && matchesInclude(file) && !matchesExclude(file)) {
                                String filePath = file.toAbsolutePath().toString();
                                currentFiles.add(filePath);

                                if (!discoveredFiles.contains(filePath)) {
                                    log.info("Discovered new log file: {}", filePath);
                                    discoveredFiles.add(filePath);
                                    activeFiles.add(filePath);
                                    if (listener != null) {
                                        listener.onFileDiscovered(filePath);
                                    }
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
                }
            } catch (Exception e) {
                log.error("Error during file discovery for pattern: {}", pathPattern, e);
            }
        }

        for (String activeFile : activeFiles) {
            if (!currentFiles.contains(activeFile)) {
                log.info("Log file removed: {}", activeFile);
                activeFiles.remove(activeFile);
                if (listener != null) {
                    listener.onFileRemoved(activeFile);
                }
            }
        }
    }

    private Path getBasePath(String pattern) {
        String base = pattern;
        int globIdx = pattern.indexOf('*');
        if (globIdx > 0) {
            base = pattern.substring(0, globIdx);
            int lastSlash = base.lastIndexOf('/');
            if (lastSlash > 0) {
                base = base.substring(0, lastSlash);
            }
        }
        if (base.isEmpty() || base.equals("/")) {
            base = "/var/log";
        }
        return Paths.get(base);
    }

    private boolean matchesInclude(Path file) {
        if (includeMatchers.isEmpty()) {
            return file.toString().endsWith(".log");
        }
        for (PathMatcher matcher : includeMatchers) {
            if (matcher.matches(file)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesExclude(Path file) {
        for (PathMatcher matcher : excludeMatchers) {
            if (matcher.matches(file)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> getActiveFiles() {
        return new HashSet<>(activeFiles);
    }

    public void close() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
