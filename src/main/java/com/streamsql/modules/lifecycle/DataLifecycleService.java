package com.streamsql.modules.lifecycle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streamsql.common.PageResult;
import com.streamsql.dto.LifecyclePolicyDTO;
import com.streamsql.entity.DataArchiveRecord;
import com.streamsql.entity.DatasourceInfo;
import com.streamsql.entity.LifecyclePolicy;
import com.streamsql.mapper.DataArchiveRecordMapper;
import com.streamsql.mapper.DatasourceInfoMapper;
import com.streamsql.mapper.LifecyclePolicyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.zip.GZIPOutputStream;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataLifecycleService {

    private final LifecyclePolicyMapper lifecyclePolicyMapper;
    private final DataArchiveRecordMapper dataArchiveRecordMapper;
    private final DatasourceInfoMapper datasourceInfoMapper;
    private final ObjectMapper objectMapper;

    @Value("${streamsql.lifecycle.archive-enabled:true}")
    private boolean archiveEnabled;

    @Value("${streamsql.lifecycle.cleanup-enabled:true}")
    private boolean cleanupEnabled;

    private static final String ARCHIVE_BASE_PATH = "./data/archive";

    @Transactional(rollbackFor = Exception.class)
    public LifecyclePolicy createPolicy(LifecyclePolicyDTO dto) {
        LifecyclePolicy policy = new LifecyclePolicy();
        policy.setPolicyName(dto.getPolicyName());
        policy.setDatasourceId(dto.getDatasourceId());
        policy.setTableName(dto.getTableName());
        policy.setHotStorageDays(dto.getHotStorageDays());
        policy.setColdStorageDays(dto.getColdStorageDays());
        policy.setArchiveStorageDays(dto.getArchiveStorageDays());
        policy.setEnabled(dto.getEnabled());

        lifecyclePolicyMapper.insert(policy);
        return policy;
    }

    @Transactional(rollbackFor = Exception.class)
    public LifecyclePolicy updatePolicy(String policyId, LifecyclePolicyDTO dto) {
        LifecyclePolicy policy = lifecyclePolicyMapper.selectById(policyId);
        if (policy == null) {
            throw new IllegalArgumentException("策略不存在: " + policyId);
        }

        policy.setPolicyName(dto.getPolicyName());
        policy.setDatasourceId(dto.getDatasourceId());
        policy.setTableName(dto.getTableName());
        policy.setHotStorageDays(dto.getHotStorageDays());
        policy.setColdStorageDays(dto.getColdStorageDays());
        policy.setArchiveStorageDays(dto.getArchiveStorageDays());
        policy.setEnabled(dto.getEnabled());

        lifecyclePolicyMapper.updateById(policy);
        return policy;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deletePolicy(String policyId) {
        lifecyclePolicyMapper.deleteById(policyId);
    }

    public LifecyclePolicy getPolicy(String policyId) {
        return lifecyclePolicyMapper.selectById(policyId);
    }

    public PageResult<LifecyclePolicy> listPolicies(int page, int size, String datasourceId, Boolean enabled) {
        LambdaQueryWrapper<LifecyclePolicy> wrapper = new LambdaQueryWrapper<>();
        if (datasourceId != null) {
            wrapper.eq(LifecyclePolicy::getDatasourceId, datasourceId);
        }
        if (enabled != null) {
            wrapper.eq(LifecyclePolicy::getEnabled, enabled);
        }
        wrapper.orderByDesc(LifecyclePolicy::getCreatedAt);

        IPage<LifecyclePolicy> pageResult = lifecyclePolicyMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(pageResult.getRecords(), pageResult.getTotal(), page, size);
    }

    @Transactional(rollbackFor = Exception.class)
    @Scheduled(cron = "0 0 2 * * *")
    public void executeLifecyclePolicies() {
        if (!archiveEnabled && !cleanupEnabled) {
            return;
        }

        log.info("Starting lifecycle policy execution...");
        List<LifecyclePolicy> policies = lifecyclePolicyMapper.selectList(
                new LambdaQueryWrapper<LifecyclePolicy>()
                        .eq(LifecyclePolicy::getEnabled, true)
        );

        for (LifecyclePolicy policy : policies) {
            try {
                executePolicy(policy);
            } catch (Exception e) {
                log.error("Failed to execute lifecycle policy: {}", policy.getPolicyId(), e);
            }
        }

        log.info("Lifecycle policy execution completed");
    }

    @Transactional(rollbackFor = Exception.class)
    public void executePolicy(LifecyclePolicy policy) throws JsonProcessingException {
        log.info("Executing lifecycle policy: {}", policy.getPolicyName());

        DatasourceInfo datasource = datasourceInfoMapper.selectById(policy.getDatasourceId());
        if (datasource == null) {
            log.warn("Datasource not found for policy: {}", policy.getPolicyId());
            return;
        }

        Map<String, Object> connConfig = objectMapper.readValue(datasource.getConnectionConfig(), Map.class);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime coldThreshold = now.minus(policy.getHotStorageDays(), ChronoUnit.DAYS);
        LocalDateTime archiveThreshold = now.minus(policy.getColdStorageDays(), ChronoUnit.DAYS);
        LocalDateTime cleanupThreshold = now.minus(policy.getArchiveStorageDays(), ChronoUnit.DAYS);

        if (archiveEnabled) {
            long archivedCount = archiveOldData(policy, datasource, connConfig, archiveThreshold, "archive");
            log.info("Archived {} records for policy: {}", archivedCount, policy.getPolicyName());
        }

        if (cleanupEnabled) {
            long deletedCount = cleanupExpiredData(policy, datasource, connConfig, cleanupThreshold);
            log.info("Cleaned up {} records for policy: {}", deletedCount, policy.getPolicyName());
        }

        policy.setLastMigrateTime(LocalDateTime.now());
        lifecyclePolicyMapper.updateById(policy);
    }

    private long archiveOldData(LifecyclePolicy policy, DatasourceInfo datasource,
                                 Map<String, Object> connConfig, LocalDateTime threshold, String archiveType) {
        String tableName = policy.getTableName();
        long count = 0;

        try (Connection conn = getConnection(datasource.getDatasourceType(), connConfig)) {
            String dateColumn = getDateColumn(conn, tableName);
            if (dateColumn == null) {
                log.warn("No date column found for table: {}", tableName);
                return 0;
            }

            String selectSql = String.format(
                    "SELECT * FROM %s WHERE %s < ?",
                    tableName, dateColumn
            );

            List<Map<String, Object>> recordsToArchive = new ArrayList<>();
            try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                pstmt.setObject(1, threshold);
                try (ResultSet rs = pstmt.executeQuery()) {
                    int columnCount = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        Map<String, Object> record = new LinkedHashMap<>();
                        for (int i = 1; i <= columnCount; i++) {
                            record.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
                        }
                        recordsToArchive.add(record);
                    }
                }
            }

            if (!recordsToArchive.isEmpty()) {
                String archivePath = writeArchiveFile(policy, recordsToArchive, archiveType);
                count = recordsToArchive.size();

                DataArchiveRecord archiveRecord = new DataArchiveRecord();
                archiveRecord.setPolicyId(policy.getPolicyId());
                archiveRecord.setDatasourceId(policy.getDatasourceId());
                archiveRecord.setTableName(tableName);
                archiveRecord.setArchiveType(archiveType);
                archiveRecord.setArchivePath(archivePath);
                archiveRecord.setArchiveCount(count);
                archiveRecord.setArchiveDate(LocalDate.now());
                dataArchiveRecordMapper.insert(archiveRecord);

                String deleteSql = String.format(
                        "DELETE FROM %s WHERE %s < ?",
                        tableName, dateColumn
                );
                try (PreparedStatement pstmt = conn.prepareStatement(deleteSql)) {
                    pstmt.setObject(1, threshold);
                    pstmt.executeUpdate();
                }
            }

        } catch (Exception e) {
            log.error("Failed to archive data for table: {}", tableName, e);
        }

        return count;
    }

    private String writeArchiveFile(LifecyclePolicy policy, List<Map<String, Object>> records, String archiveType) throws IOException {
        String dateStr = LocalDate.now().toString();
        String dirPath = ARCHIVE_BASE_PATH + "/" + policy.getDatasourceId() + "/" + policy.getTableName() + "/" + archiveType;
        Files.createDirectories(Paths.get(dirPath));

        String fileName = policy.getTableName() + "_" + dateStr + "_" + System.currentTimeMillis() + ".json.gz";
        Path filePath = Paths.get(dirPath, fileName);

        try (GZIPOutputStream gzos = new GZIPOutputStream(new FileOutputStream(filePath.toFile()));
             OutputStreamWriter writer = new OutputStreamWriter(gzos)) {

            for (Map<String, Object> record : records) {
                writer.write(objectMapper.writeValueAsString(record));
                writer.write("\n");
            }
        }

        return filePath.toString();
    }

    private long cleanupExpiredData(LifecyclePolicy policy, DatasourceInfo datasource,
                                     Map<String, Object> connConfig, LocalDateTime threshold) {
        String tableName = policy.getTableName();
        long count = 0;

        try (Connection conn = getConnection(datasource.getDatasourceType(), connConfig)) {
            String dateColumn = getDateColumn(conn, tableName);
            if (dateColumn == null) {
                return 0;
            }

            String countSql = String.format(
                    "SELECT COUNT(*) as cnt FROM %s WHERE %s < ?",
                    tableName, dateColumn
            );

            try (PreparedStatement pstmt = conn.prepareStatement(countSql)) {
                pstmt.setObject(1, threshold);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        count = rs.getLong("cnt");
                    }
                }
            }

            if (count > 0) {
                String deleteSql = String.format(
                        "DELETE FROM %s WHERE %s < ?",
                        tableName, dateColumn
                );
                try (PreparedStatement pstmt = conn.prepareStatement(deleteSql)) {
                    pstmt.setObject(1, threshold);
                    pstmt.executeUpdate();
                }
            }

        } catch (Exception e) {
            log.error("Failed to cleanup expired data for table: {}", tableName, e);
        }

        return count;
    }

    private String getDateColumn(Connection conn, String tableName) {
        try {
            ResultSet rs = conn.getMetaData().getColumns(null, null, tableName, null);
            List<String> dateColumns = new ArrayList<>();

            while (rs.next()) {
                String colName = rs.getString("COLUMN_NAME");
                String colType = rs.getString("TYPE_NAME").toLowerCase();
                if (colType.contains("date") || colType.contains("time")) {
                    dateColumns.add(colName);
                }
            }

            for (String col : Arrays.asList("created_at", "create_time", "created", "updated_at", "update_time")) {
                if (dateColumns.contains(col)) {
                    return col;
                }
            }

            return dateColumns.isEmpty() ? null : dateColumns.get(0);
        } catch (Exception e) {
            log.warn("Failed to find date column for table: {}", tableName, e);
            return null;
        }
    }

    private Connection getConnection(String datasourceType, Map<String, Object> config) throws Exception {
        String jdbcUrl;
        String driverClass;

        switch (datasourceType.toLowerCase()) {
            case "mysql":
                driverClass = "com.mysql.cj.jdbc.Driver";
                jdbcUrl = String.format("jdbc:mysql://%s:%s/%s?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai",
                        config.getOrDefault("host", "localhost"),
                        config.getOrDefault("port", "3306"),
                        config.getOrDefault("database", ""));
                break;
            case "postgresql":
                driverClass = "org.postgresql.Driver";
                jdbcUrl = String.format("jdbc:postgresql://%s:%s/%s",
                        config.getOrDefault("host", "localhost"),
                        config.getOrDefault("port", "5432"),
                        config.getOrDefault("database", ""));
                break;
            default:
                throw new IllegalArgumentException("不支持的数据源类型: " + datasourceType);
        }

        try {
            Class.forName(driverClass);
        } catch (ClassNotFoundException e) {
            log.warn("Driver class not found: {}", driverClass);
        }

        return DriverManager.getConnection(jdbcUrl,
                (String) config.get("username"),
                (String) config.get("password"));
    }

    public PageResult<DataArchiveRecord> listArchiveRecords(int page, int size, String policyId, String datasourceId) {
        LambdaQueryWrapper<DataArchiveRecord> wrapper = new LambdaQueryWrapper<>();
        if (policyId != null) {
            wrapper.eq(DataArchiveRecord::getPolicyId, policyId);
        }
        if (datasourceId != null) {
            wrapper.eq(DataArchiveRecord::getDatasourceId, datasourceId);
        }
        wrapper.orderByDesc(DataArchiveRecord::getArchiveDate);

        IPage<DataArchiveRecord> pageResult = dataArchiveRecordMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(pageResult.getRecords(), pageResult.getTotal(), page, size);
    }

    @Transactional(rollbackFor = Exception.class)
    public void migrateToColdStorage(String policyId) throws JsonProcessingException {
        LifecyclePolicy policy = lifecyclePolicyMapper.selectById(policyId);
        if (policy == null) {
            throw new IllegalArgumentException("策略不存在: " + policyId);
        }

        DatasourceInfo datasource = datasourceInfoMapper.selectById(policy.getDatasourceId());
        if (datasource == null) {
            throw new IllegalArgumentException("数据源不存在: " + policy.getDatasourceId());
        }

        Map<String, Object> connConfig = objectMapper.readValue(datasource.getConnectionConfig(), Map.class);
        LocalDateTime threshold = LocalDateTime.now().minus(policy.getHotStorageDays(), ChronoUnit.DAYS);

        log.info("Migrating data to cold storage for policy: {}, threshold: {}", policy.getPolicyName(), threshold);
        archiveOldData(policy, datasource, connConfig, threshold, "cold");

        policy.setLastMigrateTime(LocalDateTime.now());
        lifecyclePolicyMapper.updateById(policy);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cleanupExpired(String policyId) throws JsonProcessingException {
        LifecyclePolicy policy = lifecyclePolicyMapper.selectById(policyId);
        if (policy == null) {
            throw new IllegalArgumentException("策略不存在: " + policyId);
        }

        DatasourceInfo datasource = datasourceInfoMapper.selectById(policy.getDatasourceId());
        if (datasource == null) {
            throw new IllegalArgumentException("数据源不存在: " + policy.getDatasourceId());
        }

        Map<String, Object> connConfig = objectMapper.readValue(datasource.getConnectionConfig(), Map.class);
        LocalDateTime threshold = LocalDateTime.now().minus(policy.getArchiveStorageDays(), ChronoUnit.DAYS);

        log.info("Cleaning up expired data for policy: {}, threshold: {}", policy.getPolicyName(), threshold);
        cleanupExpiredData(policy, datasource, connConfig, threshold);
    }

    public Map<String, Object> getStorageStatistics(String datasourceId) {
        Map<String, Object> stats = new LinkedHashMap<>();

        List<LifecyclePolicy> policies = lifecyclePolicyMapper.selectList(
                new LambdaQueryWrapper<LifecyclePolicy>()
                        .eq(datasourceId != null, LifecyclePolicy::getDatasourceId, datasourceId)
        );

        long totalArchivedRecords = 0;
        long totalArchiveFiles = 0;

        for (LifecyclePolicy policy : policies) {
            List<DataArchiveRecord> records = dataArchiveRecordMapper.selectList(
                    new LambdaQueryWrapper<DataArchiveRecord>()
                            .eq(DataArchiveRecord::getPolicyId, policy.getPolicyId())
            );

            totalArchiveFiles += records.size();
            totalArchivedRecords += records.stream()
                    .mapToLong(DataArchiveRecord::getArchiveCount)
                    .sum();
        }

        stats.put("policyCount", policies.size());
        stats.put("archiveFileCount", totalArchiveFiles);
        stats.put("archivedRecordCount", totalArchivedRecords);

        return stats;
    }
}
