package com.loganalytics.storage.postgres;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.common.util.JsonUtils;
import com.loganalytics.storage.config.StorageConfig;
import com.loganalytics.storage.minio.MinioArchiveManager;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class MetadataIndexManager implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MetadataIndexManager.class);

    private final StorageConfig config;
    private final HikariDataSource dataSource;
    private final BlockingQueue<IndexEntry> writeQueue;
    private final ExecutorService writerThreadPool;
    private final ScheduledExecutorService flushScheduler;
    private final AtomicLong totalIndexed = new AtomicLong(0);
    private final AtomicLong writeFailures = new AtomicLong(0);
    private volatile boolean running;

    public static class IndexEntry {
        private final LogEvent event;
        private final MinioArchiveManager.ArchiveResult archiveResult;

        public IndexEntry(LogEvent event, MinioArchiveManager.ArchiveResult archiveResult) {
            this.event = event;
            this.archiveResult = archiveResult;
        }

        public LogEvent getEvent() { return event; }
        public MinioArchiveManager.ArchiveResult getArchiveResult() { return archiveResult; }
    }

    public static class LogSearchResult {
        private final LogEvent event;
        private final String minioLocation;
        private final long minioOffset;
        private final int minioLength;

        public LogSearchResult(LogEvent event, String minioLocation, long minioOffset, int minioLength) {
            this.event = event;
            this.minioLocation = minioLocation;
            this.minioOffset = minioOffset;
            this.minioLength = minioLength;
        }

        public LogEvent getEvent() { return event; }
        public String getMinioLocation() { return minioLocation; }
        public long getMinioOffset() { return minioOffset; }
        public int getMinioLength() { return minioLength; }
    }

    private static final String INSERT_LOG_INDEX_SQL =
            "INSERT INTO log_index (time, service_name, level, pattern_id, trace_id, " +
            "hostname, minio_object, minio_offset, minio_length, message, message_tsvector) " +
            "VALUES (?, ?, ?::log_level, ?, ?, ?, ?, ?, ?, ?, to_tsvector('english', ?))";

    private static final String CREATE_HYPERTABLE_SQL =
            "SELECT create_hypertable('log_index', 'time', if_not_exists => TRUE)";

    private static final String CREATE_RETENTION_SQL =
            "SELECT add_retention_policy('log_index', INTERVAL '%d days', if_not_exists => TRUE)";

    private static final String CREATE_INDEX_SERVICE_TIME_SQL =
            "CREATE INDEX IF NOT EXISTS idx_log_index_service_time " +
            "ON log_index (service_name, time DESC)";

    private static final String CREATE_INDEX_LEVEL_TIME_SQL =
            "CREATE INDEX IF NOT EXISTS idx_log_index_level_time " +
            "ON log_index (level, time DESC)";

    private static final String CREATE_INDEX_PATTERN_TIME_SQL =
            "CREATE INDEX IF NOT EXISTS idx_log_index_pattern_time " +
            "ON log_index (pattern_id, time DESC)";

    private static final String CREATE_INDEX_TRACE_ID_SQL =
            "CREATE INDEX IF NOT EXISTS idx_log_index_trace_id " +
            "ON log_index (trace_id)";

    private static final String CREATE_INDEX_FULLTEXT_SQL =
            "CREATE INDEX IF NOT EXISTS idx_log_index_message_tsv " +
            "ON log_index USING GIN (message_tsvector)";

    private static final String SEARCH_SQL_BASE =
            "SELECT time, service_name, level, pattern_id, trace_id, hostname, " +
            "minio_object, minio_offset, minio_length, message " +
            "FROM log_index WHERE 1=1";

    public MetadataIndexManager(StorageConfig config) {
        this.config = config;
        this.dataSource = createDataSource();
        this.writeQueue = new ArrayBlockingQueue<>(config.getMaxQueueSize());
        this.writerThreadPool = Executors.newFixedThreadPool(config.getPostgresPoolSize());
        this.flushScheduler = Executors.newSingleThreadScheduledExecutor();
        this.running = true;

        initializeDatabase();
        startWriterThreads();
        startFlushScheduler();

        log.info("PostgreSQL metadata index manager initialized");
    }

    private HikariDataSource createDataSource() {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.getPostgresUrl());
        hc.setUsername(config.getPostgresUser());
        hc.setPassword(config.getPostgresPassword());
        hc.setMaximumPoolSize(config.getPostgresPoolSize());
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
            executeSQL(conn, CREATE_INDEX_SERVICE_TIME_SQL);
            executeSQL(conn, CREATE_INDEX_LEVEL_TIME_SQL);
            executeSQL(conn, CREATE_INDEX_PATTERN_TIME_SQL);
            executeSQL(conn, CREATE_INDEX_TRACE_ID_SQL);

            if (config.isEnableFullTextSearch()) {
                executeSQL(conn, CREATE_INDEX_FULLTEXT_SQL);
            }

            if (config.getRawDataRetentionDays() > 0 && config.getRawDataRetentionDays() < Integer.MAX_VALUE) {
                String sql = String.format(CREATE_RETENTION_SQL, config.getRawDataRetentionDays());
                executeSQL(conn, sql);
            }

            log.info("PostgreSQL initialized successfully");

        } catch (Exception e) {
            log.warn("PostgreSQL initialization error (may be already initialized): {}", e.getMessage());
        }
    }

    private void executeSQL(Connection conn, String sql) throws SQLException {
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.execute();
        }
    }

    private void startWriterThreads() {
        for (int i = 0; i < config.getPostgresPoolSize(); i++) {
            writerThreadPool.submit(this::writerLoop);
        }
        log.info("Started {} PostgreSQL writer threads", config.getPostgresPoolSize());
    }

    private void startFlushScheduler() {
        flushScheduler.scheduleAtFixedRate(
                this::flushIfNeeded,
                config.getFlushInterval().getSeconds(),
                config.getFlushInterval().getSeconds(),
                TimeUnit.SECONDS
        );
    }

    private void writerLoop() {
        List<IndexEntry> batch = new ArrayList<>();
        long lastFlushTime = System.currentTimeMillis();

        while (running || !writeQueue.isEmpty()) {
            try {
                IndexEntry entry = writeQueue.poll(100, TimeUnit.MILLISECONDS);

                if (entry != null) {
                    batch.add(entry);
                }

                long now = System.currentTimeMillis();
                if (batch.size() >= config.getBatchSize() ||
                        (now - lastFlushTime) >= config.getFlushInterval().toMillis()) {
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

    private void writeBatch(List<IndexEntry> batch) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(INSERT_LOG_INDEX_SQL)) {

            for (IndexEntry entry : batch) {
                LogEvent event = entry.getEvent();
                MinioArchiveManager.ArchiveResult ar = entry.getArchiveResult();

                Instant timestamp = event.getTimestamp() != null ? event.getTimestamp() : Instant.now();

                stmt.setTimestamp(1, Timestamp.from(timestamp));
                stmt.setString(2, event.getServiceName() != null ? event.getServiceName() : "unknown");
                stmt.setString(3, event.getLevel() != null ? event.getLevel().name() : "UNKNOWN");
                stmt.setString(4, event.getPatternId());
                stmt.setString(5, event.getTraceId());
                stmt.setString(6, event.getHostname());

                if (ar != null) {
                    stmt.setString(7, ar.getObjectName());
                    stmt.setLong(8, ar.getOffset());
                    stmt.setInt(9, ar.getLength());
                } else {
                    stmt.setString(7, null);
                    stmt.setNull(8, Types.BIGINT);
                    stmt.setNull(9, Types.INTEGER);
                }

                String message = event.getMessage() != null ?
                        (event.getMessage().length() > 10000 ?
                                event.getMessage().substring(0, 10000) : event.getMessage()) : "";
                stmt.setString(10, message);
                stmt.setString(11, message);

                stmt.addBatch();
            }

            int[] results = stmt.executeBatch();
            int inserted = 0;
            for (int r : results) {
                if (r > 0) inserted++;
            }

            totalIndexed.addAndGet(inserted);
            log.debug("Indexed {} log entries", inserted);

        } catch (Exception e) {
            writeFailures.addAndGet(batch.size());
            log.error("Error writing batch of {} entries: {}", batch.size(), e.getMessage());
        }
    }

    public void index(LogEvent event, MinioArchiveManager.ArchiveResult archiveResult) {
        if (!running || event == null) return;

        try {
            boolean offered = writeQueue.offer(new IndexEntry(event, archiveResult), 100, TimeUnit.MILLISECONDS);
            if (!offered) {
                log.warn("Index queue full, dropping index for event: {}", event.getId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void flushIfNeeded() {
        if (writeQueue.size() > config.getBatchSize() / 2) {
            log.debug("Flush triggered: queue size = {}", writeQueue.size());
        }
    }

    public List<LogSearchResult> search(Map<String, Object> filters, int limit, int offset) {
        List<LogSearchResult> results = new ArrayList<>();
        StringBuilder sql = new StringBuilder(SEARCH_SQL_BASE);
        List<Object> params = new ArrayList<>();

        buildWhereClause(filters, sql, params);

        sql.append(" ORDER BY time DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    LogSearchResult result = mapToSearchResult(rs);
                    results.add(result);
                }
            }

        } catch (Exception e) {
            log.error("Error searching logs: {}", e.getMessage(), e);
        }

        return results;
    }

    public long count(Map<String, Object> filters) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM log_index WHERE 1=1");
        List<Object> params = new ArrayList<>();

        buildWhereClause(filters, sql, params);

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql.toString())) {

            for (int i = 0; i < params.size(); i++) {
                stmt.setObject(i + 1, params.get(i));
            }

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }

        } catch (Exception e) {
            log.error("Error counting logs: {}", e.getMessage(), e);
        }

        return 0;
    }

    private void buildWhereClause(Map<String, Object> filters, StringBuilder sql, List<Object> params) {
        if (filters == null) return;

        if (filters.containsKey("serviceName")) {
            sql.append(" AND service_name = ?");
            params.add(filters.get("serviceName"));
        }

        if (filters.containsKey("level")) {
            Object level = filters.get("level");
            if (level instanceof Collection) {
                sql.append(" AND level IN (");
                Collection<?> levels = (Collection<?>) level;
                for (int i = 0; i < levels.size(); i++) {
                    if (i > 0) sql.append(", ");
                    sql.append("?::log_level");
                    params.add(levels.get(i).toString());
                }
                sql.append(")");
            } else {
                sql.append(" AND level = ?::log_level");
                params.add(level.toString());
            }
        }

        if (filters.containsKey("patternId")) {
            sql.append(" AND pattern_id = ?");
            params.add(filters.get("patternId"));
        }

        if (filters.containsKey("traceId")) {
            sql.append(" AND trace_id = ?");
            params.add(filters.get("traceId"));
        }

        if (filters.containsKey("hostname")) {
            sql.append(" AND hostname = ?");
            params.add(filters.get("hostname"));
        }

        if (filters.containsKey("timeStart")) {
            sql.append(" AND time >= ?");
            params.add(toTimestamp(filters.get("timeStart")));
        }

        if (filters.containsKey("timeEnd")) {
            sql.append(" AND time <= ?");
            params.add(toTimestamp(filters.get("timeEnd")));
        }

        if (filters.containsKey("fulltext") && config.isEnableFullTextSearch()) {
            sql.append(" AND message_tsvector @@ plainto_tsquery('english', ?)");
            params.add(filters.get("fulltext"));
        }

        if (filters.containsKey("messageContains")) {
            sql.append(" AND message ILIKE ?");
            params.add("%" + filters.get("messageContains") + "%");
        }
    }

    private Timestamp toTimestamp(Object value) {
        if (value instanceof Instant) {
            return Timestamp.from((Instant) value);
        } else if (value instanceof LocalDateTime) {
            return Timestamp.valueOf((LocalDateTime) value);
        } else if (value instanceof Long) {
            return Timestamp.from(Instant.ofEpochMilli((Long) value));
        } else if (value instanceof String) {
            return Timestamp.from(Instant.parse((String) value));
        }
        throw new IllegalArgumentException("Unsupported timestamp type: " + value.getClass());
    }

    private LogSearchResult mapToSearchResult(ResultSet rs) throws SQLException {
        Instant time = rs.getTimestamp("time").toInstant();
        String serviceName = rs.getString("service_name");
        LogLevel level = LogLevel.fromString(rs.getString("level"));
        String patternId = rs.getString("pattern_id");
        String traceId = rs.getString("trace_id");
        String hostname = rs.getString("hostname");
        String minioObject = rs.getString("minio_object");
        long minioOffset = rs.getLong("minio_offset");
        int minioLength = rs.getInt("minio_length");
        String message = rs.getString("message");

        LogEvent event = new LogEvent();
        event.setTimestamp(time);
        event.setServiceName(serviceName);
        event.setLevel(level);
        event.setPatternId(patternId);
        event.setTraceId(traceId);
        event.setHostname(hostname);
        event.setMessage(message);

        return new LogSearchResult(event, minioObject, minioOffset, minioLength);
    }

    public List<LogSearchResult> getByTraceId(String traceId, int limit) {
        Map<String, Object> filters = new HashMap<>();
        filters.put("traceId", traceId);
        return search(filters, limit, 0);
    }

    public List<LogSearchResult> getByServiceAndTime(
            String serviceName, Instant timeStart, Instant timeEnd, int limit, int offset) {
        Map<String, Object> filters = new HashMap<>();
        filters.put("serviceName", serviceName);
        filters.put("timeStart", timeStart);
        filters.put("timeEnd", timeEnd);
        return search(filters, limit, offset);
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public void close() {
        running = false;
        log.info("Shutting down PostgreSQL metadata index manager...");

        flushScheduler.shutdown();
        try {
            if (!flushScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                flushScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            flushScheduler.shutdownNow();
        }

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
            log.warn("Dropping {} remaining index entries in queue during shutdown", remaining);
        }

        dataSource.close();
        log.info("PostgreSQL metadata index manager shutdown complete. Total indexed: {}, failures: {}",
                totalIndexed.get(), writeFailures.get());
    }

    public Map<String, Object> getDiagnostics() {
        return Map.of(
                "totalIndexed", totalIndexed.get(),
                "writeFailures", writeFailures.get(),
                "queueSize", writeQueue.size(),
                "queueCapacity", writeQueue.remainingCapacity(),
                "poolSize", dataSource.getHikariPoolMXBean().getTotalConnections(),
                "activeConnections", dataSource.getHikariPoolMXBean().getActiveConnections(),
                "fullTextSearchEnabled", config.isEnableFullTextSearch()
        );
    }

    public boolean isRunning() {
        return running;
    }
}
