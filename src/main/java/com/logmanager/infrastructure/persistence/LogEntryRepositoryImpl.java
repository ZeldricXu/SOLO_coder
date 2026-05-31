package com.logmanager.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.logmanager.common.enums.LogLevel;
import com.logmanager.domain.model.LogEntry;
import com.logmanager.domain.repository.LogEntryRepository;
import com.logmanager.infrastructure.persistence.entity.LogEntryPO;
import com.logmanager.infrastructure.persistence.mapper.LogEntryMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class LogEntryRepositoryImpl implements LogEntryRepository {

    private final LogEntryMapper mapper;

    @Override
    public Mono<LogEntry> save(LogEntry logEntry) {
        LogEntryPO po = toPO(logEntry);
        if (po.getId() == null) {
            po.setId(UUID.randomUUID().toString());
        }
        mapper.insert(po);
        return Mono.just(toDomain(po));
    }

    @Override
    public Flux<LogEntry> saveAll(Iterable<LogEntry> logEntries) {
        return Flux.fromIterable(logEntries)
                .flatMap(this::save);
    }

    @Override
    public Flux<LogEntry> findByServiceName(String serviceName) {
        LambdaQueryWrapper<LogEntryPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LogEntryPO::getServiceName, serviceName);
        List<LogEntryPO> pos = mapper.selectList(wrapper);
        return Flux.fromIterable(pos).map(this::toDomain);
    }

    @Override
    public Flux<LogEntry> findByServiceNameAndLevel(String serviceName, LogLevel level) {
        LambdaQueryWrapper<LogEntryPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LogEntryPO::getServiceName, serviceName)
                .eq(LogEntryPO::getLevel, level.getDisplayName());
        List<LogEntryPO> pos = mapper.selectList(wrapper);
        return Flux.fromIterable(pos).map(this::toDomain);
    }

    @Override
    public Flux<LogEntry> findByTraceId(String traceId) {
        LambdaQueryWrapper<LogEntryPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LogEntryPO::getTraceId, traceId);
        List<LogEntryPO> pos = mapper.selectList(wrapper);
        return Flux.fromIterable(pos).map(this::toDomain);
    }

    @Override
    public Flux<LogEntry> findByTimeRange(Instant start, Instant end) {
        LambdaQueryWrapper<LogEntryPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.between(LogEntryPO::getTimestamp, start, end);
        List<LogEntryPO> pos = mapper.selectList(wrapper);
        return Flux.fromIterable(pos).map(this::toDomain);
    }

    @Override
    public Mono<Long> countByServiceName(String serviceName) {
        LambdaQueryWrapper<LogEntryPO> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(LogEntryPO::getServiceName, serviceName);
        Long count = mapper.selectCount(wrapper);
        return Mono.just(count);
    }

    private LogEntryPO toPO(LogEntry domain) {
        LogEntryPO po = new LogEntryPO();
        po.setId(domain.getId());
        po.setTraceId(domain.getTraceId());
        po.setServiceName(domain.getServiceName());
        po.setLevel(domain.getLevel() != null ? domain.getLevel().getDisplayName() : null);
        po.setMessage(domain.getMessage());
        po.setLoggerName(domain.getLoggerName());
        po.setThreadName(domain.getThreadName());
        po.setTimestamp(domain.getTimestamp());
        po.setTags(domain.getTags());
        po.setMetadata(domain.getMetadata());
        po.setAttributes(domain.getAttributes());
        po.setCreatedAt(domain.getCreatedAt());
        po.setUpdatedAt(domain.getUpdatedAt());
        return po;
    }

    private LogEntry toDomain(LogEntryPO po) {
        LogEntry domain = new LogEntry();
        domain.setId(po.getId());
        domain.setTraceId(po.getTraceId());
        domain.setServiceName(po.getServiceName());
        domain.setLevel(po.getLevel() != null ? LogLevel.fromString(po.getLevel()) : null);
        domain.setMessage(po.getMessage());
        domain.setLoggerName(po.getLoggerName());
        domain.setThreadName(po.getThreadName());
        domain.setTimestamp(po.getTimestamp());
        domain.setTags(po.getTags());
        domain.setMetadata(po.getMetadata());
        domain.setAttributes(po.getAttributes());
        domain.setCreatedAt(po.getCreatedAt());
        domain.setUpdatedAt(po.getUpdatedAt());
        return domain;
    }
}
