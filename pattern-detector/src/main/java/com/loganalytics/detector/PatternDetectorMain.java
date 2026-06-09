package com.loganalytics.detector;

import com.loganalytics.common.config.AppConfig;
import com.loganalytics.common.config.KafkaTopics;
import com.loganalytics.common.model.AnomalyEvent;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogPattern;
import com.loganalytics.common.serde.AnomalyEventSerde;
import com.loganalytics.common.serde.LogEventSerde;
import com.loganalytics.detector.anomaly.ContentAnomalyDetector;
import com.loganalytics.detector.anomaly.CorrelationAnomalyDetector;
import com.loganalytics.detector.anomaly.FrequencyAnomalyDetector;
import com.loganalytics.detector.baseline.BaselineManager;
import com.loganalytics.detector.config.DetectorConfig;
import com.loganalytics.detector.drain.DrainTree;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.apache.kafka.streams.state.Stores;
import org.apache.kafka.streams.state.WindowStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@CommandLine.Command(name = "pattern-detector", mixinStandardHelpOptions = true,
        description = "Pattern recognition and anomaly detection engine")
public class PatternDetectorMain implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(PatternDetectorMain.class);

    @CommandLine.Option(names = "--config", description = "Config file path")
    private String configPath;

    @CommandLine.Option(names = "--bootstrap-servers", description = "Kafka bootstrap servers")
    private String bootstrapServers;

    private DetectorConfig config;
    private DrainTree drainTree;
    private BaselineManager baselineManager;
    private FrequencyAnomalyDetector frequencyDetector;
    private ContentAnomalyDetector contentDetector;
    private CorrelationAnomalyDetector correlationDetector;
    private KafkaStreams streams;
    private ScheduledExecutorService scheduler;

    private final List<LogEvent> eventBuffer = new ArrayList<>();
    private final List<LogPattern> patternBuffer = new ArrayList<>();
    private final Object bufferLock = new Object();

    @Override
    public void run() {
        try {
            AppConfig appConfig = configPath != null ?
                    AppConfig.loadFromFile(configPath) : AppConfig.loadDefault();
            config = DetectorConfig.fromAppConfig(appConfig);

            if (bootstrapServers != null) {
                config.setBootstrapServers(bootstrapServers);
            }

            initializeComponents();
            startKafkaStreams();
            startMonitoringScheduler();
            startAnomalyDetectionScheduler();

            log.info("Pattern detector started successfully");
            log.info("  Similarity threshold: {}", config.getSimilarityThreshold());
            boolean freq = config.isFrequencyDetectionEnabled();
            boolean cont = config.isContentDetectionEnabled();
            boolean corr = config.isCorrelationDetectionEnabled();
            log.info("  Detection: FREQUENCY={}, CONTENT={}, CORRELATION={}", freq, cont, corr);

            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

            Thread.currentThread().join();

        } catch (Exception e) {
            log.error("Pattern detector failed", e);
            System.exit(1);
        }
    }

    private void initializeComponents() {
        drainTree = new DrainTree(config);
        baselineManager = new BaselineManager(config);
        frequencyDetector = new FrequencyAnomalyDetector(config, baselineManager);
        contentDetector = new ContentAnomalyDetector(config);
        correlationDetector = new CorrelationAnomalyDetector(config);
    }

    private void startKafkaStreams() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, config.getApplicationId());
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers());
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, LogEventSerde.class);
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.AT_LEAST_ONCE);
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);
        props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 10 * 1024 * 1024);
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 2);
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                "org.apache.kafka.streams.errors.LogAndContinueExceptionHandler");

        StreamsBuilder builder = new StreamsBuilder();

        String patternStore = "pattern-counts";
        builder.addStateStore(Stores.windowStoreBuilder(
                Stores.inMemoryWindowStore(
                        patternStore,
                        Duration.ofMinutes(config.getFrequencyWindowMinutes() * 10),
                        Duration.ofMinutes(config.getFrequencyWindowMinutes()),
                        false
                ),
                Serdes.String(),
                Serdes.Long()
        ));

        KStream<String, LogEvent> inputStream = builder.stream(
                config.getInputTopic(),
                org.apache.kafka.streams.kstream.Consumed.with(Serdes.String(), new LogEventSerde())
        );

        KStream<String, AnomalyEvent> anomalyStream = inputStream.processValues(
                () -> new PatternDetectionProcessor(),
                patternStore
        );

        anomalyStream.to(
                config.getOutputTopic(),
                Produced.with(Serdes.String(), new AnomalyEventSerde())
        );

        streams = new KafkaStreams(builder.build(), props);

        streams.setStateListener((newState, oldState) -> {
            log.info("Streams state changed: {} -> {}", oldState, newState);
        });

        streams.setUncaughtExceptionHandler((thread, throwable) -> {
            log.error("Uncaught exception in stream thread {}", thread.getName(), throwable);
        });

        streams.start();
    }

    private class PatternDetectionProcessor implements Processor<String, LogEvent, String, AnomalyEvent> {
        private ProcessorContext<String, AnomalyEvent> context;

        @Override
        public void init(ProcessorContext<String, AnomalyEvent> context) {
            this.context = context;
        }

        @Override
        public void process(Record<String, LogEvent> record) {
            LogEvent event = record.value();
            if (event == null || event.getMessage() == null) return;

            LogPattern pattern = drainTree.process(event);
            if (pattern == null) return;

            synchronized (bufferLock) {
                eventBuffer.add(event);
                if (!patternBuffer.contains(pattern)) {
                    patternBuffer.add(pattern);
                }
            }

            List<AnomalyEvent> anomalies = runDetection(event, pattern);
            for (AnomalyEvent anomaly : anomalies) {
                context.forward(new Record<>(
                        anomaly.getPatternId(),
                        anomaly,
                        anomaly.getTimestamp().toEpochMilli()
                ));
            }
        }

        private List<AnomalyEvent> runDetection(LogEvent event, LogPattern pattern) {
            List<AnomalyEvent> anomalies = new ArrayList<>();

            if (config.isContentDetectionEnabled()) {
                List<LogPattern> patterns = List.of(pattern);
                List<LogEvent> events = List.of(event);
                anomalies.addAll(contentDetector.detect(patterns, events));
            }

            if (config.isCorrelationDetectionEnabled()) {
                anomalies.addAll(correlationDetector.detect(List.of(event)));
            }

            return anomalies;
        }

        @Override
        public void close() {}
    }

    private void startMonitoringScheduler() {
        scheduler = Executors.newScheduledThreadPool(2);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                log.info("=== Pattern Detector Stats ===");
                log.info("  Total patterns: {}", drainTree.getPatternCount());
                log.info("  Top 5 patterns:");
                List<LogPattern> top5 = drainTree.getTopKPatterns(5);
                for (int i = 0; i < top5.size(); i++) {
                    LogPattern p = top5.get(i);
                    log.info("    {}. [{}] {} - {} total",
                            i + 1, p.getSampleLevel(),
                            p.getTemplate().length() > 80 ?
                                    p.getTemplate().substring(0, 80) + "..." : p.getTemplate(),
                            p.getTotalCount());
                }
                log.info("  Frequency detector: {}", frequencyDetector.getDiagnostics());
                log.info("  Content detector: {}", contentDetector.getDiagnostics());
                log.info("  Correlation detector: {}", correlationDetector.getDiagnostics());

            } catch (Exception e) {
                log.error("Error in monitoring scheduler", e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private void startAnomalyDetectionScheduler() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                baselineManager.rotateWindow();

                if (config.isFrequencyDetectionEnabled()) {
                    synchronized (bufferLock) {
                        if (!patternBuffer.isEmpty()) {
                            List<LogPattern> patterns = new ArrayList<>(patternBuffer);
                            List<AnomalyEvent> anomalies = frequencyDetector.detect(patterns);

                            if (!anomalies.isEmpty() && streams != null) {
                                log.warn("Detected {} frequency anomalies", anomalies.size());
                            }

                            patternBuffer.clear();
                            eventBuffer.clear();
                        }
                    }
                }

            } catch (Exception e) {
                log.error("Error in anomaly detection scheduler", e);
            }
        }, config.getFrequencyWindowMinutes(), config.getFrequencyWindowMinutes(), TimeUnit.MINUTES);
    }

    private void shutdown() {
        log.info("Shutting down pattern detector...");

        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }

        if (streams != null) {
            streams.close(Duration.ofSeconds(10));
        }

        log.info("Pattern detector shutdown complete");
    }

    public static void main(String[] args) {
        new CommandLine(new PatternDetectorMain()).execute(args);
    }
}
