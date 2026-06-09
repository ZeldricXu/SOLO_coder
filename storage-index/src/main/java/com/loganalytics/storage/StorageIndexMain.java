package com.loganalytics.storage;

import com.loganalytics.common.config.AppConfig;
import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.serde.LogEventSerde;
import com.loganalytics.storage.config.StorageConfig;
import com.loganalytics.storage.minio.MinioArchiveManager;
import com.loganalytics.storage.postgres.MetadataIndexManager;
import com.loganalytics.storage.query.QueryCoordinator;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.processor.api.Processor;
import org.apache.kafka.streams.processor.api.ProcessorContext;
import org.apache.kafka.streams.processor.api.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@CommandLine.Command(name = "storage-index", mixinStandardHelpOptions = true,
        description = "Log storage and index management service")
public class StorageIndexMain implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(StorageIndexMain.class);

    @CommandLine.Option(names = "--config", description = "Config file path")
    private String configPath;

    @CommandLine.Option(names = "--bootstrap-servers", description = "Kafka bootstrap servers")
    private String bootstrapServers;

    private StorageConfig config;
    private MinioArchiveManager minioManager;
    private MetadataIndexManager indexManager;
    private QueryCoordinator queryCoordinator;
    private KafkaStreams streams;
    private ScheduledExecutorService scheduler;

    private final AtomicLong eventsProcessed = new AtomicLong(0);
    private final AtomicLong archivedToMinio = new AtomicLong(0);
    private final AtomicLong indexedToPostgres = new AtomicLong(0);

    @Override
    public void run() {
        try {
            AppConfig appConfig = configPath != null ?
                    AppConfig.loadFromFile(configPath) : AppConfig.loadDefault();
            config = StorageConfig.fromAppConfig(appConfig);

            if (bootstrapServers != null) {
                config.setBootstrapServers(bootstrapServers);
            }

            initializeComponents();
            startKafkaStreams();
            startMonitoringScheduler();

            log.info("Storage index service started successfully");
            log.info("  MinIO: {} (enabled: {})", config.getMinioEndpoint(), config.isEnableMinio());
            log.info("  PostgreSQL: {} (enabled: {})", config.getPostgresUrl(), config.isEnablePostgres());
            log.info("  Full text search: {}", config.isEnableFullTextSearch());
            log.info("  Batch size: {}, flush interval: {}s",
                    config.getBatchSize(), config.getFlushInterval().getSeconds());
            log.info("  Retention: raw data {} days", config.getRawDataRetentionDays());

            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

            Thread.currentThread().join();

        } catch (Exception e) {
            log.error("Storage index service failed", e);
            System.exit(1);
        }
    }

    private void initializeComponents() throws Exception {
        if (config.isEnableMinio()) {
            minioManager = new MinioArchiveManager(config);
        }

        if (config.isEnablePostgres()) {
            indexManager = new MetadataIndexManager(config);
        }

        queryCoordinator = new QueryCoordinator(config, minioManager, indexManager);
    }

    private void startKafkaStreams() {
        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, config.getApplicationId());
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers());
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, LogEventSerde.class);
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.AT_LEAST_ONCE);
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 5000);
        props.put(StreamsConfig.CACHE_MAX_BYTES_BUFFERING_CONFIG, 10 * 1024 * 1024);
        props.put(StreamsConfig.NUM_STREAM_THREADS_CONFIG, 2);
        props.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                "org.apache.kafka.streams.errors.LogAndContinueExceptionHandler");

        StreamsBuilder builder = new StreamsBuilder();

        KStream<String, LogEvent> inputStream = builder.stream(
                config.getInputTopic(),
                org.apache.kafka.streams.kstream.Consumed.with(Serdes.String(), new LogEventSerde())
        );

        inputStream.processValues(
                () -> new Processor<String, LogEvent, String, LogEvent>() {
                    private ProcessorContext<String, LogEvent> context;

                    @Override
                    public void init(ProcessorContext<String, LogEvent> context) {
                        this.context = context;
                    }

                    @Override
                    public void process(Record<String, LogEvent> record) {
                        LogEvent event = record.value();
                        if (event == null) return;

                        eventsProcessed.incrementAndGet();

                        MinioArchiveManager.ArchiveResult archiveResult = null;

                        if (config.isEnableMinio() && minioManager != null) {
                            archiveResult = minioManager.archive(event);
                            if (archiveResult != null) {
                                archivedToMinio.incrementAndGet();
                            }
                        }

                        if (config.isEnablePostgres() && indexManager != null) {
                            indexManager.index(event, archiveResult);
                            indexedToPostgres.incrementAndGet();
                        }

                        context.forward(record);
                    }

                    @Override
                    public void close() {}
                }
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
        scheduler = Executors.newScheduledThreadPool(1);

        scheduler.scheduleAtFixedRate(() -> {
            try {
                log.info("=== Storage Index Stats ===");
                log.info("  Events processed: {}", eventsProcessed.get());
                log.info("  Archived to MinIO: {}", archivedToMinio.get());
                log.info("  Indexed to PostgreSQL: {}", indexedToPostgres.get());

                if (minioManager != null) {
                    log.info("  MinIO: {}", minioManager.getDiagnostics());
                }
                if (indexManager != null) {
                    log.info("  PostgreSQL: {}", indexManager.getDiagnostics());
                }
                if (queryCoordinator != null) {
                    log.info("  Query coordinator: {}", queryCoordinator.getDiagnostics());
                }

            } catch (Exception e) {
                log.error("Error in monitoring scheduler", e);
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    private void shutdown() {
        log.info("Shutting down storage index service...");

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

        if (queryCoordinator != null) {
            queryCoordinator.shutdown();
        }

        if (indexManager != null) {
            try {
                indexManager.close();
            } catch (Exception e) {
                log.error("Error closing index manager", e);
            }
        }

        if (minioManager != null) {
            try {
                minioManager.close();
            } catch (Exception e) {
                log.error("Error closing minio manager", e);
            }
        }

        log.info("Storage index service shutdown complete");
    }

    public QueryCoordinator getQueryCoordinator() {
        return queryCoordinator;
    }

    public static void main(String[] args) {
        new CommandLine(new StorageIndexMain()).execute(args);
    }
}
