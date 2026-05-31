package com.delivery.tracker.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.delivery.tracker.entity.AuditLog;
import com.delivery.tracker.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditLogMapper;
    private static final String GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    public Mono<AuditLog> log(String operation, String resourceType, String resourceId, Map<String, Object> detail) {
        return log("system", operation, resourceType, resourceId, detail);
    }

    public Mono<AuditLog> log(String userId, String operation, String resourceType, String resourceId, Map<String, Object> detail) {
        return Mono.fromCallable(() -> {
            String previousHash = getLastHash();

            AuditLog auditLog = new AuditLog();
            auditLog.setLogId("log_" + UUID.randomUUID().toString().substring(0, 8));
            auditLog.setUserId(userId);
            auditLog.setOperation(operation);
            auditLog.setResourceType(resourceType);
            auditLog.setResourceId(resourceId);
            auditLog.setDetail(detail);
            auditLog.setPreviousHash(previousHash);

            String currentHash = calculateHash(auditLog);
            auditLog.setCurrentHash(currentHash);

            auditLogMapper.insert(auditLog);
            log.debug("审计日志已记录: logId={}, operation={}", auditLog.getLogId(), operation);
            return auditLog;
        });
    }

    private String getLastHash() {
        AuditLog lastLog = auditLogMapper.selectOne(
                new LambdaQueryWrapper<AuditLog>()
                        .orderByDesc(AuditLog::getCreatedAt)
                        .last("LIMIT 1")
        );
        return lastLog != null ? lastLog.getCurrentHash() : GENESIS_HASH;
    }

    private String calculateHash(AuditLog auditLog) {
        String data = auditLog.getLogId() + "|"
                + auditLog.getUserId() + "|"
                + auditLog.getOperation() + "|"
                + auditLog.getResourceType() + "|"
                + auditLog.getResourceId() + "|"
                + (auditLog.getDetail() != null ? auditLog.getDetail().toString() : "") + "|"
                + auditLog.getPreviousHash() + "|"
                + auditLog.getCreatedAt();

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256算法不可用", e);
        }
    }

    public Flux<AuditLog> getLogs(String resourceType, String resourceId, LocalDateTime startTime, LocalDateTime endTime) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<AuditLog> query = new LambdaQueryWrapper<>();
            if (resourceType != null) {
                query.eq(AuditLog::getResourceType, resourceType);
            }
            if (resourceId != null) {
                query.eq(AuditLog::getResourceId, resourceId);
            }
            if (startTime != null) {
                query.ge(AuditLog::getCreatedAt, startTime);
            }
            if (endTime != null) {
                query.le(AuditLog::getCreatedAt, endTime);
            }
            query.orderByDesc(AuditLog::getCreatedAt);
            return auditLogMapper.selectList(query);
        })
                .flatMapMany(Flux::fromIterable);
    }

    public Mono<Boolean> verifyIntegrity() {
        return Mono.fromCallable(() -> {
            List<AuditLog> logs = auditLogMapper.selectList(
                    new LambdaQueryWrapper<AuditLog>()
                            .orderByAsc(AuditLog::getCreatedAt)
            );

            if (logs.isEmpty()) {
                return true;
            }

            String expectedPreviousHash = GENESIS_HASH;
            for (AuditLog log : logs) {
                if (!expectedPreviousHash.equals(log.getPreviousHash())) {
                    log.error("审计日志哈希链断裂: logId={}, expectedPreviousHash={}, actualPreviousHash={}",
                            log.getLogId(), expectedPreviousHash, log.getPreviousHash());
                    return false;
                }

                String recalculatedHash = calculateHash(log);
                if (!recalculatedHash.equals(log.getCurrentHash())) {
                    log.error("审计日志内容被篡改: logId={}, expectedHash={}, actualHash={}",
                            log.getLogId(), recalculatedHash, log.getCurrentHash());
                    return false;
                }

                expectedPreviousHash = log.getCurrentHash();
            }

            log.info("审计日志完整性验证通过，共{}条记录", logs.size());
            return true;
        });
    }

    public Mono<AuditLog> getLog(String logId) {
        return Mono.fromCallable(() ->
                auditLogMapper.selectOne(
                        new LambdaQueryWrapper<AuditLog>()
                                .eq(AuditLog::getLogId, logId)
                )
        );
    }

    public Mono<Map<String, Object>> getChainInfo() {
        return Mono.fromCallable(() -> {
            Long total = auditLogMapper.selectCount(null);
            AuditLog lastLog = auditLogMapper.selectOne(
                    new LambdaQueryWrapper<AuditLog>()
                            .orderByDesc(AuditLog::getCreatedAt)
                            .last("LIMIT 1")
            );

            return Map.of(
                    "totalLogs", total,
                    "lastHash", lastLog != null ? lastLog.getCurrentHash() : GENESIS_HASH,
                    "lastLogTime", lastLog != null ? lastLog.getCreatedAt() : null,
                    "genesisHash", GENESIS_HASH
            );
        });
    }
}
