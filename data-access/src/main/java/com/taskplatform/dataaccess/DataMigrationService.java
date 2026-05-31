package com.taskplatform.dataaccess;

import com.taskplatform.common.exception.BusinessException;
import com.taskplatform.common.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataMigrationService {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public Map<String, Object> getMigrationStatus() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();

        MigrationInfo[] migrations = flyway.info().all();

        Map<String, Object> status = new HashMap<>();
        List<Map<String, Object>> migrationList = new ArrayList<>();

        for (MigrationInfo migration : migrations) {
            Map<String, Object> info = new HashMap<>();
            info.put("version", migration.getVersion() != null ? migration.getVersion().getVersion() : null);
            info.put("description", migration.getDescription());
            info.put("type", migration.getType());
            info.put("state", migration.getState());
            info.put("installedOn", migration.getInstalledOn());
            info.put("executionTime", migration.getExecutionTime());
            info.put("success", migration.getState().isApplied());
            migrationList.add(info);
        }

        status.put("migrations", migrationList);
        status.put("total", migrations.length);
        status.put("applied", Arrays.stream(migrations).filter(m -> m.getState().isApplied()).count());
        status.put("pending", Arrays.stream(migrations).filter(m -> m.getState().isPending()).count());
        status.put("failed", Arrays.stream(migrations).filter(m -> m.getState().isFailed()).count());
        status.put("currentVersion", flyway.info().current() != null ?
                flyway.info().current().getVersion().getVersion() : null);

        return status;
    }

    public Map<String, Object> runMigrations() {
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load();

            int migrated = flyway.migrate();
            log.info("Successfully applied {} migrations", migrated);

            Map<String, Object> result = new HashMap<>();
            result.put("migrationsApplied", migrated);
            result.put("currentVersion", flyway.info().current() != null ?
                    flyway.info().current().getVersion().getVersion() : null);
            result.put("success", true);

            return result;
        } catch (Exception e) {
            log.error("Migration failed", e);
            throw new BusinessException(500, "MIGRATION_FAILED", e.getMessage());
        }
    }

    public Map<String, Object> baseline(String version) {
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .baselineVersion(MigrationVersion.fromVersion(version))
                    .load();

            flyway.baseline();
            log.info("Baseline created at version: {}", version);

            Map<String, Object> result = new HashMap<>();
            result.put("version", version);
            result.put("success", true);
            return result;
        } catch (Exception e) {
            log.error("Baseline creation failed", e);
            throw new BusinessException(500, "BASELINE_FAILED", e.getMessage());
        }
    }

    public Map<String, Object> repair() {
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load();

            flyway.repair();
            log.info("Flyway repair completed");

            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            result.put("message", "Flyway schema history table repaired");
            return result;
        } catch (Exception e) {
            log.error("Repair failed", e);
            throw new BusinessException(500, "REPAIR_FAILED", e.getMessage());
        }
    }

    public Map<String, Object> exportData(String tableName) {
        try {
            List<Map<String, Object>> data = jdbcTemplate.queryForList(
                    "SELECT * FROM " + tableName + " WHERE is_deleted = 0"
            );

            Map<String, Object> result = new HashMap<>();
            result.put("table", tableName);
            result.put("rowCount", data.size());
            result.put("data", data);
            result.put("exportedAt", LocalDateTime.now().toString());

            return result;
        } catch (Exception e) {
            log.error("Data export failed for table: {}", tableName, e);
            throw new BusinessException(500, "EXPORT_FAILED", e.getMessage());
        }
    }

    public Map<String, Object> importData(String tableName, List<Map<String, Object>> data) {
        if (data == null || data.isEmpty()) {
            throw new BusinessException(400, "EMPTY_DATA", "No data to import");
        }

        int imported = 0;
        for (Map<String, Object> row : data) {
            try {
                StringBuilder columns = new StringBuilder();
                StringBuilder placeholders = new StringBuilder();
                List<Object> values = new ArrayList<>();

                for (Map.Entry<String, Object> entry : row.entrySet()) {
                    if (columns.length() > 0) {
                        columns.append(", ");
                        placeholders.append(", ");
                    }
                    columns.append(entry.getKey());
                    placeholders.append("?");
                    values.add(entry.getValue());
                }

                String sql = String.format("INSERT INTO %s (%s) VALUES (%s)",
                        tableName, columns, placeholders);
                jdbcTemplate.update(sql, values.toArray());
                imported++;
            } catch (Exception e) {
                log.warn("Failed to import row: {}", row, e);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("table", tableName);
        result.put("imported", imported);
        result.put("total", data.size());
        result.put("success", imported == data.size());

        return result;
    }

    public List<String> listTables() {
        return jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()",
                String.class
        );
    }

    public Map<String, Object> getTableInfo(String tableName) {
        Map<String, Object> info = new HashMap<>();
        info.put("tableName", tableName);

        try {
            Long rowCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + tableName, Long.class
            );
            info.put("rowCount", rowCount);

            List<Map<String, Object>> columns = jdbcTemplate.queryForList(
                    "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT " +
                            "FROM information_schema.COLUMNS " +
                            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                    tableName
            );
            info.put("columns", columns);
        } catch (Exception e) {
            info.put("error", e.getMessage());
        }

        return info;
    }
}
