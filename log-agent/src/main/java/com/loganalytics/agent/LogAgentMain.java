package com.loganalytics.agent;

import com.loganalytics.agent.config.AgentConfig;
import com.loganalytics.agent.discovery.FileDiscovery;
import com.loganalytics.agent.input.FileTailer;
import com.loganalytics.agent.input.SocketReceiver;
import com.loganalytics.agent.input.StdoutCapturer;
import com.loganalytics.agent.kafka.KafkaProducerManager;
import com.loganalytics.agent.multiline.MultiLineMerger;
import com.loganalytics.agent.offset.OffsetManager;
import com.loganalytics.common.config.AppConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@CommandLine.Command(
        name = "log-agent",
        description = "Log Analytics Agent - Collects logs from various sources and sends to Kafka"
)
public class LogAgentMain implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(LogAgentMain.class);

    @CommandLine.Option(names = {"-c", "--config"}, description = "Config file path")
    private String configFile;

    @CommandLine.Option(names = {"-s", "--service"}, description = "Service name")
    private String serviceName;

    @CommandLine.Option(names = {"-p", "--paths"}, description = "Log file paths (comma-separated)")
    private String logPaths;

    @CommandLine.Option(names = {"-k", "--kafka"}, description = "Kafka bootstrap servers")
    private String kafkaServers;

    @CommandLine.Option(names = {"--socket-port"}, description = "Socket receiver port")
    private Integer socketPort;

    @CommandLine.Option(names = {"--stdout"}, description = "Capture stdout")
    private boolean captureStdout;

    @CommandLine.Option(names = {"--pid"}, description = "Process ID for stdout capture")
    private Long pid;

    private AgentConfig config;
    private AppConfig appConfig;
    private OffsetManager offsetManager;
    private KafkaProducerManager producerManager;
    private FileDiscovery fileDiscovery;
    private SocketReceiver socketReceiver;
    private StdoutCapturer stdoutCapturer;
    private final Map<String, FileTailer> tailers = new ConcurrentHashMap<>();
    private final Map<String, MultiLineMerger> fileMergers = new ConcurrentHashMap<>();
    private ScheduledExecutorService tailerScheduler;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private volatile boolean running;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new LogAgentMain()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        try {
            initialize();
            startComponents();
            registerShutdownHook();
            awaitShutdown();
        } catch (Exception e) {
            log.error("Fatal error in log agent", e);
            System.exit(1);
        }
    }

    private void initialize() throws Exception {
        appConfig = configFile != null ? new AppConfig(configFile) : new AppConfig();

        if (serviceName != null) {
            appConfig.asProperties().setProperty("agent.service.name", serviceName);
        }
        if (logPaths != null) {
            appConfig.asProperties().setProperty("agent.log.paths", logPaths);
        }
        if (kafkaServers != null) {
            appConfig.asProperties().setProperty("kafka.bootstrap.servers", kafkaServers);
        }
        if (socketPort != null) {
            appConfig.asProperties().setProperty("agent.socket.port", String.valueOf(socketPort));
        }
        if (captureStdout) {
            appConfig.asProperties().setProperty("agent.stdout.capture.enabled", "true");
        }
        if (pid != null) {
            appConfig.asProperties().setProperty("agent.stdout.pid", String.valueOf(pid));
        }

        config = AgentConfig.fromAppConfig(appConfig);
        offsetManager = new OffsetManager(config.getOffsetStorePath());
        producerManager = new KafkaProducerManager(config);
        tailerScheduler = Executors.newScheduledThreadPool(
                Math.min(8, Runtime.getRuntime().availableProcessors()),
                r -> {
                    Thread t = new Thread(r, "file-tailer");
                    t.setDaemon(true);
                    return t;
                }
        );

        log.info("Log Agent initialized for service: {}", config.getServiceName());
    }

    private void startComponents() throws IOException {
        producerManager.start();
        running = true;

        fileDiscovery = new FileDiscovery(config, new FileDiscovery.FileDiscoveryListener() {
            @Override
            public void onFileDiscovered(String filePath) {
                startFileTailer(filePath);
            }

            @Override
            public void onFileRemoved(String filePath) {
                stopFileTailer(filePath);
            }
        });
        fileDiscovery.start();

        socketReceiver = new SocketReceiver(config, this::handleLogEvent);
        socketReceiver.start();

        if (config.isStdoutCaptureEnabled()) {
            MultiLineMerger stdoutMerger = new MultiLineMerger(config);
            stdoutCapturer = new StdoutCapturer(config, stdoutMerger, this::handleLogEvent);
            stdoutCapturer.start();
        }

        startTailerLoop();
        log.info("All components started");
    }

    private void startFileTailer(String filePath) {
        try {
            MultiLineMerger merger = new MultiLineMerger(config);
            fileMergers.put(filePath, merger);

            FileTailer tailer = new FileTailer(config, filePath, offsetManager, merger, this::handleLogEvent);
            tailers.put(filePath, tailer);

            log.info("Started tailer for {}", filePath);
        } catch (Exception e) {
            log.error("Failed to start tailer for {}", filePath, e);
        }
    }

    private void stopFileTailer(String filePath) {
        FileTailer tailer = tailers.remove(filePath);
        if (tailer != null) {
            tailer.close();
            log.info("Stopped tailer for {}", filePath);
        }
        fileMergers.remove(filePath);
        offsetManager.removeOffset(filePath);
    }

    private void startTailerLoop() {
        tailerScheduler.scheduleAtFixedRate(() -> {
            if (!running) return;

            for (Map.Entry<String, FileTailer> entry : tailers.entrySet()) {
                FileTailer tailer = entry.getValue();
                if (!tailer.isRunning()) {
                    stopFileTailer(entry.getKey());
                    continue;
                }

                try {
                    tailer.tail();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error tailing {}", entry.getKey(), e);
                }
            }
        }, 0, config.getTailPollInterval().toMillis(), TimeUnit.MILLISECONDS);

        tailerScheduler.scheduleAtFixedRate(() -> {
            log.info("Agent stats - files: {}, sent: {}, failed: {}",
                    tailers.size(), producerManager.getTotalSent(), producerManager.getTotalFailed());
        }, 60, 60, TimeUnit.SECONDS);
    }

    private void handleLogEvent(com.loganalytics.common.model.LogEvent event) {
        if (!running) return;
        producerManager.send(event);
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            stopComponents();
            shutdownLatch.countDown();
        }, "shutdown-hook"));
    }

    private void stopComponents() {
        running = false;
        log.info("Stopping components...");

        if (tailerScheduler != null) {
            tailerScheduler.shutdown();
            try {
                if (!tailerScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    tailerScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        tailers.forEach((path, tailer) -> tailer.close());
        tailers.clear();
        fileMergers.clear();

        if (stdoutCapturer != null) {
            stdoutCapturer.stop();
        }

        if (socketReceiver != null) {
            socketReceiver.stop();
        }

        if (fileDiscovery != null) {
            fileDiscovery.close();
        }

        if (producerManager != null) {
            producerManager.stop();
        }

        if (offsetManager != null) {
            offsetManager.close();
        }

        log.info("All components stopped");
    }

    private void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }
}
