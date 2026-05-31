package com.delivery.tracker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delivery.tracker.entity.SchemaMigration;
import com.delivery.tracker.mapper.SchemaMigrationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataAccessService {

    private final SchemaMigrationMapper schemaMigrationMapper;

    public Mono<SchemaMigration> executeMigration(String version, String scriptName, String scriptContent) {
        return Mono.fromCallable(() -> {
            String checksum = calculateChecksum(scriptContent);

            SchemaMigration existing = schemaMigrationMapper.selectOne(
                    new LambdaQueryWrapper<SchemaMigration>()
                            .eq(SchemaMigration::getVersion, version)
            );
            if (existing != null) {
                if (!existing.getChecksum().equals(checksum)) {
                    throw new RuntimeException("脚本校验和不匹配，版本: " + version);
                }
                existing.setStatus("SKIPPED");
                return existing;
            }

            SchemaMigration migration = new SchemaMigration();
            migration.setVersion(version);
            migration.setScriptName(scriptName);
            migration.setStatus("EXECUTING");
            migration.setChecksum(checksum);
            schemaMigrationMapper.insert(migration);

            try {
                executeScript(scriptContent);
                migration.setStatus("SUCCESS");
                migration.setExecutedAt(LocalDateTime.now());
                schemaMigrationMapper.updateById(migration);
                log.info("数据迁移执行成功: version={}", version);
            } catch (Exception e) {
                migration.setStatus("FAILED");
                schemaMigrationMapper.updateById(migration);
                log.error("数据迁移执行失败: version={}", version, e);
                throw new RuntimeException("数据迁移失败: " + e.getMessage());
            }

            return migration;
        });
    }

    public Flux<SchemaMigration> getAllMigrations() {
        return Flux.fromIterable(schemaMigrationMapper.selectList(
                new LambdaQueryWrapper<SchemaMigration>()
                        .orderByDesc(SchemaMigration::getExecutedAt)
        ));
    }

    public Mono<Boolean> validateSchemaIntegrity() {
        return Mono.fromCallable(() -> {
            List<SchemaMigration> migrations = schemaMigrationMapper.selectList(
                    new LambdaQueryWrapper<SchemaMigration>()
                            .orderByAsc(SchemaMigration::getCreatedAt)
            );

            for (SchemaMigration migration : migrations) {
                if ("FAILED".equals(migration.getStatus())) {
                    log.error("发现失败的迁移: version={}", migration.getVersion());
                    return false;
                }
            }

            log.info("Schema完整性验证通过，共{}个迁移", migrations.size());
            return true;
        });
    }

    public Mono<Void> rollbackMigration(String version) {
        return Mono.fromRunnable(() -> {
            SchemaMigration migration = schemaMigrationMapper.selectOne(
                    new LambdaQueryWrapper<SchemaMigration>()
                            .eq(SchemaMigration::getVersion, version)
            );
            if (migration == null) {
                throw new RuntimeException("迁移不存在: " + version);
            }

            try {
                executeRollbackScript(migration.getScriptName());
                schemaMigrationMapper.deleteById(migration.getId());
                log.info("迁移回滚成功: version={}", version);
            } catch (Exception e) {
                log.error("迁移回滚失败: version={}", version, e);
                throw new RuntimeException("回滚失败: " + e.getMessage());
            }
        });
    }

    private String calculateChecksum(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256算法不可用", e);
        }
    }

    private void executeScript(String scriptContent) {
        log.debug("执行脚本: {}", scriptContent.substring(0, Math.min(100, scriptContent.length())));
    }

    private void executeRollbackScript(String scriptName) {
        log.debug("执行回滚脚本: {}", scriptName);
    }

    public Mono<String> getCurrentSchemaVersion() {
        return Mono.fromCallable(() -> {
            SchemaMigration latest = schemaMigrationMapper.selectOne(
                    new LambdaQueryWrapper<SchemaMigration>()
                            .eq(SchemaMigration::getStatus, "SUCCESS")
                            .orderByDesc(SchemaMigration::getExecutedAt)
                            .last("LIMIT 1")
            );
            return latest != null ? latest.getVersion() : "0";
        });
    }
}
