package com.loganalytics.metrics;

import com.loganalytics.common.config.AppConfig;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.MetricPoint;
import com.loganalytics.common.serde.LogEventSerde;
import com.loganalytics.common.serde.MetricPointSerde;
import com.loganalytics.metrics.config.MetricsConfig;
import com.loganalytics.metrics.topk.TopKPatternTracker;
import com.loganalytics.metrics.timescale.TimescaleWriter;
import com.loganalytics.metrics.window.WindowedAggregator;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@CommandLine.Command(name = "metrics-aggregator", mixinStandardHelpOptions = true,
        description = "Metrics aggregation and timeseries storage engine")
public class MetricsAggregatorMain implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(MetricsAggregatorMain.class);

    @CommandLine.Option(names = "--config", description = "Config file path")
    private String configPath;

    @CommandLine.Option(names = "--bootstrap-servers", description = "Kafka bootstrap servers")
    private String bootstrapServers;

    private MetricsConfig config;
    private WindowedAggregator windowedAggregator;
    private TopKPatternTracker topKTracker;
    private TimescaleWriter timescaleWriter;
    private KafkaStreams streams;
    private ScheduledExecutorService scheduler;

    private final AtomicLong eventsProcessed = new AtomicLong(0);
    private final AtomicLong metricsGenerated = new AtomicLong(0);
    private final AtomicLong topKMetricsGenerated = new AtomicLong(0);

    @Override
    public void run() {
        try {
            AppConfig appConfig = configPath != null ?
                    AppConfig.loadFromFile(configPath) : AppConfig.loadDefault();
            config = MetricsConfig.fromAppConfig(appConfig);

            if (bootstrapServers != null) {
                config.setBootstrapServers(bootstrapServers);
            }

            initializeComponents();
            startKafkaStreams();
            startMonitoringScheduler();
            startTopKScheduler();

            log.info("Metrics aggregator started successfully");
            log.info("  Windows: {}", config.getWindows().size());
            for (MetricsConfig.WindowConfig wc : config.getWindows()) {
                log.info("    - {}: {} {} (advance: {})",
                        wc.getName(), wc.getSize(), wc.getType(),
                        wc.getAdvance() != null ? wc.getAdvance() : "none");
            }
            log.info("  TopK size: {}, interval: {}s", config.getTopKSize(),
                    config.getTopKUpdateInterval().getSeconds());
            log.info("  Retention: raw={}d, minute={}d, hour=permanent",
                    config.getRawDataRetentionDays(), config.getMinuteAggRetentionDays());

            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

            Thread.currentThread().join();

        } catch (Exception e) {
            log.error("Metrics aggregator failed", e);
            System.exit(1);
        }
    }

    private void initializeComponents() {
        windowedAggregator = new WindowedAggregator(config);
        topKTracker = new TopKPatternTracker(config);
        timescaleWriter = new TimescaleWriter(config);
    }

    private void startKafkaStreams() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, config.getApplicationId());
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers());
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, LogEventSerde.class);
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.AT_LEAST_ONCE);
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);
        props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 50 * 1024 * 1024);
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, config.getAggregationParallelism());
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                "org.apache.kafka.streams.errors.LogAndContinueExceptionHandler");

        StreamsBuilder builder = new StreamsBuilder();

        KStream<String, LogEvent> inputStream = builder.stream(
                config.getInputTopic(),
                org.apache.kafka.streams.kstream.Consumed.with(Serdes.String(), new LogEventSerde())
        );

        KStream<String, LogEvent> countedStream = inputStream.processValues(
                () -> new Processor<String, LogEvent, String, LogEvent>() {
                    private ProcessorContext<String, LogEvent> context;

                    @Override
                    public void init(ProcessorContext<String, LogEvent> context) {
                        this.context = context;
                    }

                    @Override
                    public void process(Record<String, LogEvent> record) {
                        LogEvent event = record.value();
                        if (event != null) {
                            eventsProcessed.incrementAndGet();
                            topKTracker.process(event);
                            context.forward(record);
                        }
                    }

                    @Override
                    public void close() {}
                }
        );

        KStream<String, MetricPoint> windowedMetrics = windowedAggregator.buildAggregationPipeline(countedStream);

        KStream<String, MetricPoint> countedMetrics = windowedMetrics.processValues(
                () -> new Processor<String, MetricPoint, String, MetricPoint>() {
                    private ProcessorContext<String, MetricPoint> context;

                    @Override
                    public void init(ProcessorContext<String, MetricPoint> context) {
                        this.context = context;
                    }

                    @Override
                    public void process(Record<String, MetricPoint> record) {
                        MetricPoint metric = record.value();
                        if (metric != null) {
                            metricsGenerated.incrementAndGet();
                            timescaleWriter.write(metric);
                            context.forward(record);
                        }
                    }

                    @Override
                    public void close() {}
                }
        );

        countedMetrics.to(
                config.getOutputTopic(),
                Produced.with(Serdes.String(), new MetricPointSerde())
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

    private void startMonitoringScheduler() {
        scheduler = Executors.newScheduledThreadPool(2);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                log.info("=== Metrics Aggregator Stats ===");
                log.info("  Events processed: {}", eventsProcessed.get());
                log.info("  Window metrics generated: {}", metricsGenerated.get());
                log.info("  TopK metrics generated: {}", topKMetricsGenerated.get());
                log.info("  TopK tracker: {}", topKTracker.getDiagnostics());
                log.info("  Timescale writer: {}", timescaleWriter.getDiagnostics());

                List<java.util.Map<String, Object>> topK = topKTracker.getCurrentTopKWithDetails();
                if (!topK.isEmpty()) {
                    log.info("  Current Top 5 patterns:");
                    for (int i = 0; i < Math.min(5, topK.size()); i++) {
                        java.util.Map<String, Object> entry = topK.get(i);
                        String template = (String) entry.get("template");
                        if (template != null && template.length() > 80) {
                            template = template.substring(0, 80) + "...";
                        }
                        log.info("    {}. [{}] {} - {}",
                                entry.get("rank"), entry.get("count"),
                                entry.get("patternId"), template);
                    }
                }

            } catch (Exception e) {
                log.error("Error in monitoring scheduler", e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private void startTopKScheduler() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                List<MetricPoint> topKMetrics = topKTracker.getTopKMetrics();
                if (!topKMetrics.isEmpty()) {
                    for (MetricPoint metric : topKMetrics) {
                        timescaleWriter.write(metric);
                    }
                    topKMetricsGenerated.addAndGet(topKMetrics.size());
                    log.debug("Generated {} TopK metrics", topKMetrics.size());
                }
            } catch (Exception e) {
                log.error("Error in TopK scheduler", e);
            }
        }, config.getTopKUpdateInterval().getSeconds(),
                config.getTopKUpdateInterval().getSeconds(),
                TimeUnit.SECONDS);
    }

    private void shutdown() {
        log.info("Shutting down metrics aggregator...");

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

        if (timescaleWriter != null) {
            timescaleWriter.close();
        }

        log.info("Metrics aggregator shutdown complete");
    }

    public static void main(String[] args) {
        new CommandLine(new MetricsAggregatorMain()).execute(args);
    }
}
