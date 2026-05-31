package com.apishield.audit.infrastructure.repository;

import com.apishield.audit.domain.model.AuditLog;
import com.apishield.audit.domain.repository.AuditLogRepository;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Repository
public class InMemoryAuditLogRepository implements AuditLogRepository {

    private final Map<String, AuditLog> store = new ConcurrentHashMap<>();

    @Override
    public AuditLog save(AuditLog log) {
        store.put(log.getLogId(), log);
        return log;
    }

    @Override
    public Optional<AuditLog> findById(String logId) {
        return Optional.ofNullable(store.get(logId));
    }

    @Override
    public List<AuditLog> findByOperatorId(String operatorId, int page, int size) {
        return store.values().stream()
                .filter(l -> operatorId.equals(l.getOperatorId()))
                .sorted((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
                .skip((long) (page - 1) * size)
                .limit(size)
                .collect(Collectors.toList());
    }

    @Override
    public List<AuditLog> findByResource(String resourceType, String resourceId) {
        return store.values().stream()
                .filter(l -> resourceType.equals(l.getResourceType()) && resourceId.equals(l.getResourceId()))
                .sorted((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
                .collect(Collectors.toList());
    }

    @Override
    public List<AuditLog> findByTimeRange(long startTime, long endTime) {
        return store.values().stream()
                .filter(l -> l.getTimestamp() >= startTime && l.getTimestamp() <= endTime)
                .sorted((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
                .collect(Collectors.toList());
    }

    @Override
    public List<AuditLog> findByOperation(String operation, int page, int size) {
        return store.values().stream()
                .filter(l -> operation.equals(l.getOperation()))
                .sorted((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()))
                .skip((long) (page - 1) * size)
                .limit(size)
                .collect(Collectors.toList());
    }

    @Override
    public List<AuditLog> findByBlockHeightRange(int startHeight, int endHeight) {
        return store.values().stream()
                .filter(l -> l.getBlockHeight() >= startHeight && l.getBlockHeight() <= endHeight)
                .sorted(Comparator.comparingInt(AuditLog::getBlockHeight))
                .collect(Collectors.toList());
    }

    @Override
    public List<AuditLog> findAll() {
        return new ArrayList<>(store.values());
    }

    @Override
    public List<AuditLog> findByIds(List<String> logIds) {
        return logIds.stream()
                .map(store::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }
}
