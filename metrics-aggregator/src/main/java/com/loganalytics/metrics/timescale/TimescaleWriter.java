package com.loganalytics.metrics.timescale;

import com.loganalytics.common.model.MetricPoint;
import com.loganalytics.metrics.config.MetricsConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class TimescaleWriter implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(TimescaleWriter.class);

    private final MetricsConfig config;
    private final HikariDataSource dataSource;
    private final BlockingQueue<MetricPoint> writeQueue;
    private final ExecutorService writerThreadPool;
    private volatile boolean running;

    private static final String INSERT_METRIC_SQL =
            "INSERT INTO metrics (time, metric_name, value, metric_type, tags, service, window) " +
            "VALUES (?, ?, ?, ?::metric_type, ?::jsonb, ?, ?)";

    private static final String CREATE_HYPERTABLE_SQL =
            "SELECT create_hypertable('metrics', 'time', if_not_exists => TRUE)";

    private static final String CREATE_RAW_RETENTION_SQL =
            "SELECT add_retention_policy('metrics', INTERVAL '%d days', if_not_exists => TRUE)";

    private static final String CREATE_MINUTE_CAGG_SQL =
            "CREATE MATERIALIZED VIEW metrics_minute " +
            "WITH (timescaledb.continuous) AS " +
            "SELECT time_bucket('1 minute', time) as bucket, " +
            "metric_name, service, window, metric_type, " +
            "avg(value) as avg_value, sum(value) as sum_value, " +
            "max(value) as max_value, min(value) as min_value, " +
            "count(*) as sample_count " +
            "FROM metrics " +
            "GROUP BY bucket, metric_name, service, window, metric_type " +
            "WITH NO DATA";

    private static final String CREATE_HOUR_CAGG_SQL =
            "CREATE MATERIALIZED VIEW metrics_hour " +
            "WITH (timescaledb.continuous) AS " +
            "SELECT time_bucket('1 hour', time) as bucket, " +
            "metric_name, service, window, metric_type, " +
            "avg(value) as avg_value, sum(value) as sum_value, " +
            "max(value) as max_value, min(value) as min_value, " +
            "count(*) as sample_count " +
            "FROM metrics " +
            "GROUP BY bucket, metric_name, service, window, metric_type " +
            "WITH NO DATA";

    private static final String ENABLE_CAGG_POLICY_SQL =
            "SELECT add_continuous_aggregate_policy('%s', " +
            "start_offset => INTERVAL '1 hour', " +
            "end_offset => INTERVAL '1 minute', " +
            "schedule_interval => INTERVAL '30 seconds', " +
            "if_not_exists => TRUE)";

    public TimescaleWriter(MetricsConfig config) {
        this.config = config;
        this.dataSource = createDataSource();
        this.writeQueue = new ArrayBlockingQueue<>(100000);
        this.writerThreadPool = Executors.newFixedThreadPool(config.getTimescalePoolSize());
        this.running = true;

        initializeDatabase();
        startWriterThreads();
    }

    private HikariDataSource createDataSource() {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.getTimescaleUrl());
        hc.setUsername(config.getTimescaleUser());
        hc.setPassword(config.getTimescalePassword());
        hc.setMaximumPoolSize(config.getTimescalePoolSize());
        hc.setMinimumIdle(2);
        hc.setIdleTimeout(60000);
        hc.setMaxLifetime(1800000);
        hc.setConnectionTimeout(5000);
        hc.setLeakDetectionThreshold(60000);
        hc.setConnectionTestQuery("SELECT 1");
        hc.addDataSourceProperty("reWriteBatchedInserts", "true");
        hc.addDataSourceProperty("batchSize", "1000");
        return new HikariDataSource(hc);
    }

    private void initializeDatabase() {
        try (Connection conn = dataSource.getConnection()) {
            executeSQL(conn, CREATE_HYPERTABLE_SQL);

            if (config.getRawDataRetentionDays() > 0 && config.getRawDataRetentionDays() < Integer.MAX_VALUE) {
                String sql = String.format(CREATE_RAW_RETENTION_SQL, config.getRawDataRetentionDays());
                executeSQL(conn, sql);
            }

            if (config.isEnableContinuousAggregation()) {
                executeSQL(conn, CREATE_MINUTE_CAGG_SQL);
                executeSQL(conn, CREATE_HOUR_CAGG_SQL);
                executeSQL(conn, String.format(ENABLE_CAGG_POLICY_SQL, "metrics_minute"));
                executeSQL(conn, String.format(ENABLE_CAGG_POLICY_SQL, "metrics_hour"));
            }

            log.info("TimescaleDB initialized successfully");

        } catch (Exception e) {
            log.warn("TimescaleDB initialization error (may be already initialized): {}", e.getMessage());
        }
    }

    private void executeSQL(Connection conn, String sql) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.execute();
        }
    }

    private void startWriterThreads() {
        for (int i = 0; i < config.getTimescalePoolSize(); i++) {
            writerThreadPool.submit(this::writerLoop);
        }
        log.info("Started {} TimescaleDB writer threads", config.getTimescalePoolSize());
    }

    private void writerLoop() {
        List<MetricPoint> batch = new java.util.ArrayList<>();
        long lastFlushTime = System.currentTimeMillis();
        long flushIntervalMs = Duration.ofSeconds(1).toMillis();
        int batchSize = 1000;

        while (running || !writeQueue.isEmpty()) {
            try {
                MetricPoint metric = writeQueue.poll(100, TimeUnit.MILLISECONDS);

                if (metric != null) {
                    batch.add(metric);
                }

                long now = System.currentTimeMillis();
                if (batch.size() >= batchSize || (now - lastFlushTime) >= flushIntervalMs) {
                    if (!batch.isEmpty()) {
                        writeBatch(batch);
                        batch.clear();
                    }
                    lastFlushTime = now;
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error in writer thread", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (!batch.isEmpty()) {
            writeBatch(batch);
        }
    }

    private void writeBatch(List<MetricPoint> batch) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_METRIC_SQL)) {

            for (MetricPoint metric : batch) {
                stmt.setTimestamp(1, Timestamp.from(metric.getTimestamp()));
                stmt.setString(2, metric.getMetricName());
                stmt.setDouble(3, metric.getValue());
                stmt.setString(4, metric.getType() != null ? metric.getType().name() : "GAUGE");

                String tagsJson = metric.getTagsAsJson();
                stmt.setString(5, tagsJson);

                stmt.setString(6, metric.getTag("service"));
                stmt.setString(7, metric.getTag("window"));

                stmt.addBatch();
            }

            int[] results = stmt.executeBatch();
            int inserted = 0;
            for (int r : results) {
                if (r > 0) inserted++;
            }

            log.debug("Wrote {} metrics to TimescaleDB", inserted);

        } catch (Exception e) {
            log.error("Error writing batch of {} metrics: {}", batch.size(), e.getMessage());
        }
    }

    public void write(MetricPoint metric) {
        if (!running) return;

        try {
            boolean offered = writeQueue.offer(metric, 100, TimeUnit.MILLISECONDS);
            if (!offered) {
                log.warn("Write queue full, dropping metric: {}", metric.getMetricName());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void writeAll(List<MetricPoint> metrics) {
        for (MetricPoint metric : metrics) {
            write(metric);
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        running = false;
        log.info("Shutting down TimescaleDB writer...");

        writerThreadPool.shutdown();
        try {
            if (!writerThreadPool.awaitTermination(30, TimeUnit.SECONDS)) {
                writerThreadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            writerThreadPool.shutdownNow();
        }

        int remaining = writeQueue.size();
        if (remaining > 0) {
            log.warn("Dropping {} remaining metrics in queue during shutdown", remaining);
        }

        dataSource.close();
        log.info("TimescaleDB writer shutdown complete");
    }

    public Map<String, Object> getDiagnostics() {
        return Map.of(
                "queueSize", writeQueue.size(),
                "queueCapacity", writeQueue.remainingCapacity(),
                "poolSize", dataSource.getHikariPoolMXBean().getTotalConnections(),
                "activeConnections", dataSource.getHikariPoolMXBean().getActiveConnections(),
                "idleConnections", dataSource.getHikariPoolMXBean().getIdleConnections()
        );
    }
}
