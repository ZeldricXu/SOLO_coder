package com.meteorology.nwp.storage;

import com.meteorology.nwp.common.NWPConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MetadataManager implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(MetadataManager.class);
    private final NWPConfig config;
    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPassword;
    private final int poolSize;
    private transient final Deque<Connection> connectionPool = new ArrayDeque<>();
    private final Map<String, PreparedStatement> stmtCache = new ConcurrentHashMap<>();
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    public static class ForecastRun implements Serializable {
        public long id;
        public Instant initTime;
        public int forecastHours;
        public String modelVersion;
        public String domainName;
        public int nx, ny, nz;
        public String status;
        public Instant startTime, endTime;
        public int currentStep;
        public double progressPct;
        public String hdfsPath;
        public Map<String, String> attributes;

        @Override
        public String toString() {
            return String.format("Run#%d[%s +%dh %s %.1f%%]",
                    id, TS_FMT.format(initTime), forecastHours, status, progressPct);
        }
    }

    public static class DatasetEntry implements Serializable {
        public long id;
        public long runId;
        public int forecastHour;
        public String variable;
        public String format;
        public long fileSize;
        public String hdfsPath;
        public Instant createdAt;
        public String checksum;
    }

    public MetadataManager(NWPConfig config) {
        this.config = config;
        this.jdbcUrl = config.getString("nwp.storage.postgres.url",
                "jdbc:postgresql://localhost:5432/nwp_metadata");
        this.dbUser = config.getString("nwp.storage.postgres.user", "nwp");
        this.dbPassword = config.getString("nwp.storage.postgres.password", "nwp_pass");
        this.poolSize = config.getInt("nwp.storage.postgres.poolSize", 5);
        initDatabase();
        logger.info("PostgreSQL元数据初始化: {} user={} pool={}", jdbcUrl, dbUser, poolSize);
    }

    private Connection acquireConn() throws SQLException {
        synchronized (connectionPool) {
            if (!connectionPool.isEmpty()) return connectionPool.pop();
        }
        Properties props = new Properties();
        props.setProperty("user", dbUser);
        props.setProperty("password", dbPassword);
        props.setProperty("ApplicationName", "NWP-Solver-Java");
        Connection c = DriverManager.getConnection(jdbcUrl, props);
        c.setAutoCommit(false);
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        return c;
    }

    private void releaseConn(Connection c) {
        if (c == null) return;
        try {
            if (!c.isClosed()) {
                synchronized (connectionPool) {
                    if (connectionPool.size() < poolSize) {
                        connectionPool.push(c);
                        return;
                    }
                }
                c.close();
            }
        } catch (SQLException ignored) {}
    }

    private void initDatabase() {
        Connection c = null;
        try {
            c = acquireConn();
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS forecast_runs (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "init_time TIMESTAMP WITH TIME ZONE NOT NULL, " +
                        "forecast_hours INTEGER NOT NULL, " +
                        "model_version VARCHAR(32), " +
                        "domain_name VARCHAR(64), " +
                        "nx INTEGER, ny INTEGER, nz INTEGER, " +
                        "status VARCHAR(16) DEFAULT 'QUEUED', " +
                        "start_time TIMESTAMP WITH TIME ZONE, " +
                        "end_time TIMESTAMP WITH TIME ZONE, " +
                        "current_step INTEGER DEFAULT 0, " +
                        "progress_pct DOUBLE PRECISION DEFAULT 0, " +
                        "hdfs_path VARCHAR(512), " +
                        "attributes JSONB, " +
                        "created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(), " +
                        "updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW())");
                s.execute("CREATE INDEX IF NOT EXISTS idx_runs_init_time ON forecast_runs(init_time DESC)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_runs_status ON forecast_runs(status)");
                s.execute("CREATE TABLE IF NOT EXISTS datasets (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "run_id BIGINT REFERENCES forecast_runs(id) ON DELETE CASCADE, " +
                        "forecast_hour INTEGER NOT NULL, " +
                        "variable VARCHAR(32) NOT NULL, " +
                        "format VARCHAR(16) NOT NULL, " +
                        "file_size BIGINT, " +
                        "hdfs_path VARCHAR(512) NOT NULL, " +
                        "checksum VARCHAR(64), " +
                        "created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW())");
                s.execute("CREATE INDEX IF NOT EXISTS idx_datasets_run ON datasets(run_id, forecast_hour)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_datasets_var ON datasets(variable, forecast_hour)");
                s.execute("CREATE TABLE IF NOT EXISTS observations (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "obs_time TIMESTAMP WITH TIME ZONE NOT NULL, " +
                        "obs_type VARCHAR(16), " +
                        "station_id VARCHAR(32), " +
                        "longitude DOUBLE PRECISION, latitude DOUBLE PRECISION, " +
                        "pressure DOUBLE PRECISION, " +
                        "variable VARCHAR(32), value DOUBLE PRECISION, " +
                        "error DOUBLE PRECISION, quality DOUBLE PRECISION, " +
                        "assimilated_run_id BIGINT REFERENCES forecast_runs(id), " +
                        "created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW())");
                s.execute("CREATE INDEX IF NOT EXISTS idx_obs_time ON observations(obs_time DESC)");
                s.execute("CREATE INDEX IF NOT EXISTS idx_obs_station ON observations(station_id, obs_time)");
                s.execute("CREATE TABLE IF NOT EXISTS verification_scores (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "run_id BIGINT REFERENCES forecast_runs(id) ON DELETE CASCADE, " +
                        "forecast_hour INTEGER, " +
                        "variable VARCHAR(32), " +
                        "rmse DOUBLE PRECISION, bias DOUBLE PRECISION, " +
                        "correlation DOUBLE PRECISION, n_points INTEGER, " +
                        "valid_time TIMESTAMP WITH TIME ZONE, " +
                        "created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW())");
                s.execute("CREATE INDEX IF NOT EXISTS idx_verif_run ON verification_scores(run_id, forecast_hour)");
            }
            c.commit();
            logger.info("PostgreSQL表结构初始化完成");
        } catch (SQLException e) {
            logger.error("数据库初始化失败: {}", e.getMessage(), e);
            safeRollback(c);
        } finally {
            releaseConn(c);
        }
    }

    public ForecastRun createRun(Instant initTime, int forecastHours, String modelVersion,
                                  String domainName, int nx, int ny, int nz) {
        ForecastRun r = new ForecastRun();
        r.initTime = initTime; r.forecastHours = forecastHours;
        r.modelVersion = modelVersion; r.domainName = domainName;
        r.nx = nx; r.ny = ny; r.nz = nz; r.status = "QUEUED";
        r.attributes = new HashMap<>();
        Connection c = null;
        try {
            c = acquireConn();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO forecast_runs(init_time,forecast_hours,model_version,domain_name," +
                            "nx,ny,nz,status,attributes) VALUES(?,?,?,?,?,?,?,?::forecast_status,?::jsonb) " +
                            "ON CONFLICT DO NOTHING RETURNING id", Statement.RETURN_GENERATED_KEYS)) {
                ps.setTimestamp(1, new Timestamp(initTime.toEpochMilli()));
                ps.setInt(2, forecastHours);
                ps.setString(3, modelVersion);
                ps.setString(4, domainName);
                ps.setInt(5, nx); ps.setInt(6, ny); ps.setInt(7, nz);
                ps.setString(8, r.status);
                Map<String, String> attrs = new HashMap<>();
                attrs.put("created_by", "NWP-Java-Solver");
                attrs.put("creation_time", Instant.now().toString());
                ps.setString(9, "{\"created_by\":\"NWP-Java-Solver\"}");
                int rows = ps.executeUpdate();
                if (rows > 0) {
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (rs.next()) r.id = rs.getLong(1);
                    }
                }
                if (r.id == 0) {
                    try (Statement s = c.createStatement();
                         ResultSet rs = s.executeQuery("SELECT currval('forecast_runs_id_seq')")) {
                        if (rs.next()) r.id = rs.getLong(1);
                    }
                }
            }
            c.commit();
            logger.info("创建预报任务 Run#{}", r.id);
        } catch (SQLException e) {
            logger.error("创建预报任务失败: {}", e.getMessage());
            safeRollback(c);
        } finally {
            releaseConn(c);
        }
        return r;
    }

    public void updateRunStatus(long runId, String status, int currentStep, double progress) {
        Connection c = null;
        try {
            c = acquireConn();
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE forecast_runs SET status=?,current_step=?,progress_pct=?," +
                            "start_time=COALESCE(start_time,NOW()),updated_at=NOW() WHERE id=?")) {
                ps.setString(1, status);
                ps.setInt(2, currentStep);
                ps.setDouble(3, progress);
                ps.setLong(4, runId);
                ps.executeUpdate();
            }
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE forecast_runs SET end_time=NOW() WHERE id=? AND end_time IS NULL")) {
                    ps.setLong(1, runId);
                    ps.executeUpdate();
                }
            }
            c.commit();
        } catch (SQLException e) {
            logger.error("更新Run状态失败: {}", e.getMessage());
            safeRollback(c);
        } finally {
            releaseConn(c);
        }
    }

    public long registerDataset(long runId, int forecastHour, String variable, String format,
                                 long fileSize, String hdfsPath) {
        Connection c = null;
        long id = -1;
        try {
            c = acquireConn();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO datasets(run_id,forecast_hour,variable,format,file_size,hdfs_path) " +
                            "VALUES(?,?,?,?,?,?) RETURNING id", Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, runId);
                ps.setInt(2, forecastHour);
                ps.setString(3, variable);
                ps.setString(4, format);
                ps.setLong(5, fileSize);
                ps.setString(6, hdfsPath);
                ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) {
                    if (rs.next()) id = rs.getLong(1);
                }
            }
            c.commit();
        } catch (SQLException e) {
            logger.error("注册数据集失败: {}", e.getMessage());
            safeRollback(c);
        } finally {
            releaseConn(c);
        }
        return id;
    }

    public void insertVerificationScore(long runId, int fHour, String variable,
                                         double rmse, double bias, double corr, int nPts, Instant validTime) {
        Connection c = null;
        try {
            c = acquireConn();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO verification_scores(run_id,forecast_hour,variable,rmse,bias,correlation,n_points,valid_time) " +
                            "VALUES(?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, runId); ps.setInt(2, fHour); ps.setString(3, variable);
                ps.setDouble(4, rmse); ps.setDouble(5, bias); ps.setDouble(6, corr);
                ps.setInt(7, nPts);
                ps.setTimestamp(8, validTime != null ? new Timestamp(validTime.toEpochMilli()) : null);
                ps.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) {
            logger.warn("写入检验分数失败: {}", e.getMessage());
            safeRollback(c);
        } finally {
            releaseConn(c);
        }
    }

    public List<ForecastRun> getRecentRuns(int limit) {
        List<ForecastRun> runs = new ArrayList<>();
        Connection c = null;
        try {
            c = acquireConn();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,init_time,forecast_hours,model_version,domain_name,nx,ny,nz," +
                            "status,start_time,end_time,current_step,progress_pct,hdfs_path " +
                            "FROM forecast_runs ORDER BY init_time DESC LIMIT ?")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) runs.add(mapRunRow(rs));
                }
            }
        } catch (SQLException e) {
            logger.error("查询任务失败: {}", e.getMessage());
        } finally {
            releaseConn(c);
        }
        return runs;
    }

    public List<DatasetEntry> findDatasets(long runId, int forecastHour, String variable) {
        List<DatasetEntry> list = new ArrayList<>();
        Connection c = null;
        try {
            c = acquireConn();
            StringBuilder sql = new StringBuilder(
                    "SELECT id,run_id,forecast_hour,variable,format,file_size,hdfs_path,created_at FROM datasets WHERE run_id=?");
            List<Object> params = new ArrayList<>();
            params.add(runId);
            if (forecastHour >= 0) { sql.append(" AND forecast_hour=?"); params.add(forecastHour); }
            if (variable != null) { sql.append(" AND variable=?"); params.add(variable); }
            sql.append(" ORDER BY forecast_hour,variable");
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) ps.setObject(i + 1, params.get(i));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        DatasetEntry d = new DatasetEntry();
                        d.id = rs.getLong(1); d.runId = rs.getLong(2);
                        d.forecastHour = rs.getInt(3); d.variable = rs.getString(4);
                        d.format = rs.getString(5); d.fileSize = rs.getLong(6);
                        d.hdfsPath = rs.getString(7);
                        Timestamp ct = rs.getTimestamp(8);
                        if (ct != null) d.createdAt = ct.toInstant();
                        list.add(d);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("查询数据集失败: {}", e.getMessage());
        } finally {
            releaseConn(c);
        }
        return list;
    }

    private ForecastRun mapRunRow(ResultSet rs) throws SQLException {
        ForecastRun r = new ForecastRun();
        r.id = rs.getLong(1);
        Timestamp it = rs.getTimestamp(2); if (it != null) r.initTime = it.toInstant();
        r.forecastHours = rs.getInt(3);
        r.modelVersion = rs.getString(4); r.domainName = rs.getString(5);
        r.nx = rs.getInt(6); r.ny = rs.getInt(7); r.nz = rs.getInt(8);
        r.status = rs.getString(9);
        Timestamp st = rs.getTimestamp(10); if (st != null) r.startTime = st.toInstant();
        Timestamp et = rs.getTimestamp(11); if (et != null) r.endTime = et.toInstant();
        r.currentStep = rs.getInt(12); r.progressPct = rs.getDouble(13);
        r.hdfsPath = rs.getString(14);
        return r;
    }

    private void safeRollback(Connection c) {
        if (c != null) try { c.rollback(); } catch (SQLException ignored) {}
    }

    public void close() {
        for (PreparedStatement ps : stmtCache.values()) {
            try { ps.close(); } catch (SQLException ignored) {}
        }
        stmtCache.clear();
        Connection c;
        while ((c = connectionPool.poll()) != null) {
            try { c.close(); } catch (SQLException ignored) {}
        }
    }
}
