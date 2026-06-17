package com.loganalytics.pipeline;

import com.loganalytics.common.config.AppConfig;
import com.loganalytics.pipeline.config.PipelineConfig;
import com.loganalytics.pipeline.processor.ProcessorChain;
import com.loganalytics.pipeline.processor.ProcessorFactory;
import com.loganalytics.pipeline.topology.PipelineTopology;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import picocli.CommandLine;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@CommandLine.Command(
        name = "stream-pipeline",
        description = "Log Analytics Stream Pipeline - Processes logs through parse/filter/enrich/route stages"
)
public class StreamPipelineMain implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(StreamPipelineMain.class);

    @CommandLine.Option(names = {"-c", "--config"}, description = "Config file path")
    private String configFile;

    @CommandLine.Option(names = {"-k", "--kafka"}, description = "Kafka bootstrap servers")
    private String kafkaServers;

    @CommandLine.Option(names = {"-a", "--app-id"}, description = "Kafka Streams application ID")
    private String applicationId;

    @CommandLine.Option(names = {"-t", "--threads"}, description = "Number of stream threads")
    private Integer numThreads;

    private PipelineConfig config;
    private AppConfig appConfig;
    private KafkaStreams streams;
    private PipelineTopology topologyBuilder;
    private final CountDownLatch shutdownLatch = new CountDownLatch(1);
    private ScheduledExecutorService monitorScheduler;

    public static void main(String[] args) {
        int exitCode = new CommandLine(new StreamPipelineMain()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        try {
            initialize();
            startPipeline();
            registerShutdownHook();
            awaitShutdown();
        } catch (Exception e) {
            log.error("Fatal error in stream pipeline", e);
            System.exit(1);
        }
    }

    private void initialize() throws Exception {
        appConfig = configFile != null ? new AppConfig(configFile) : new AppConfig();

        if (kafkaServers != null) {
            appConfig.asProperties().setProperty("kafka.bootstrap.servers", kafkaServers);
        }
        if (applicationId != null) {
            appConfig.asProperties().setProperty("pipeline.application.id", applicationId);
        }
        if (numThreads != null) {
            appConfig.asProperties().setProperty("pipeline.threads", String.valueOf(numThreads));
        }

        config = PipelineConfig.fromAppConfig(appConfig);

        ProcessorChain chain = loadProcessorChain();
        topologyBuilder = new PipelineTopology(config, chain);
        monitorScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "pipeline-monitor");
            t.setDaemon(true);
            return t;
        });

        log.info("Stream Pipeline initialized with app ID: {}", config.getApplicationId());
    }

    private void startPipeline() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, config.getApplicationId());
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers());
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, config.getNumStreamThreads());
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, config.getCommitInterval().toMillis());
        props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, config.getCacheMaxBytesBuffering());
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,
                org.apache.kafka.common.serialization.Serdes.String().getClass().getName());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG,
                com.loganalytics.common.serde.LogEventSerde.class.getName());
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                LogAndContinueExceptionHandler.class.getName());
        props.put(StreamsConfig.DEFAULT_PRODUCTION_EXCEPTION_HANDLER_CLASS_CONFIG,
                org.apache.kafka.streams.errors.DefaultProductionExceptionHandler.class.getName());
        props.put(StreamsConfig.TOPOLOGY_OPTIMIZATION_CONFIG, StreamsConfig.OPTIMIZE);
        props.put(StreamsConfig.REPLICATION_FACTOR_CONFIG, 2);
        props.put(StreamsConfig.STATE_DIR_CONFIG, "/tmp/kafka-streams");
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.AT_LEAST_ONCE);

        Topology topology = topologyBuilder.build().build();
        streams = new KafkaStreams(topology, props);

        streams.setStateListener((newState, oldState) -> {
            log.info("Stream state changed from {} to {}", oldState, newState);
        });

        streams.setUncaughtExceptionHandler((thread, throwable) -> {
            log.error("Uncaught exception in stream thread {}", thread.getName(), throwable);
        });

        streams.start();
        log.info("Stream Pipeline started");

        startMonitoring();
    }

    private void startMonitoring() {
        monitorScheduler.scheduleAtFixedRate(() -> {
            try {
                var metrics = streams.metrics();
                long processed = 0;
                long committed = 0;

                for (var entry : metrics.entrySet()) {
                    var metric = entry.getValue();
                    var metricName = entry.getKey().name();
                    if (metricName.equals("process-rate")) {
                        log.debug("Process rate: {} records/sec", metric.metricValue());
                    }
                    if (metricName.equals("commit-rate")) {
                        committed = ((Double) metric.metricValue()).longValue();
                    }
                }

                var filter = topologyBuilder.getFilter();
                var router = topologyBuilder.getRouter();

                log.info("Pipeline stats - Filtered: {}/{} ({}%), Route counts: {}",
                        filter.getTotalFiltered(), filter.getTotalProcessed(),
                        String.format("%.1f", filter.getFilterRate()),
                        router.getRouteCounts());

            } catch (Exception e) {
                log.debug("Monitor error", e);
            }
        }, 30, 60, TimeUnit.SECONDS);
    }

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received");
            stopPipeline();
            shutdownLatch.countDown();
        }, "shutdown-hook"));
    }

    @SuppressWarnings("unchecked")
    private ProcessorChain loadProcessorChain() {
        Yaml yaml = new Yaml();
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("pipeline-processors.yaml")) {
            if (is == null) {
                log.warn("pipeline-processors.yaml not found, using default chain");
                return ProcessorFactory.createChain(List.of(
                        Map.of("type", "parse", "params", Map.of()),
                        Map.of("type", "filter", "params", Map.of()),
                        Map.of("type", "enrich", "params", Map.of()),
                        Map.of("type", "route", "params", Map.of())
                ));
            }
            Map<String, Object> root = yaml.load(is);
            List<Map<String, Object>> processors = (List<Map<String, Object>>) root.get("processors");
            return ProcessorFactory.createChain(processors);
        } catch (Exception e) {
            log.error("Failed to load processor chain from YAML, using default", e);
            return ProcessorFactory.createChain(List.of(
                    Map.of("type", "parse", "params", Map.of()),
                    Map.of("type", "filter", "params", Map.of()),
                    Map.of("type", "enrich", "params", Map.of()),
                    Map.of("type", "route", "params", Map.of())
            ));
        }
    }

    private void stopPipeline() {
        log.info("Stopping stream pipeline...");

        if (monitorScheduler != null) {
            monitorScheduler.shutdown();
            try {
                if (!monitorScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    monitorScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (streams != null) {
            streams.close(java.time.Duration.ofSeconds(10));
        }

        log.info("Stream pipeline stopped");
    }

    private void awaitShutdown() throws InterruptedException {
        shutdownLatch.await();
    }
}
